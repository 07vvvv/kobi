package com.xixi.timetable.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.xixi.timetable.MainActivity
import com.xixi.timetable.data.CourseRepository
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.week.WeekPatterns
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 提醒调度。
 *
 * 核心设计（直接决定提醒会不会被系统推迟）：
 *
 * 1. [rescheduleAll] / [cancelAll] 对外是**同步方法**，但内部自己丢到 IO 线程执行，
 *    绝不阻塞调用方（`KobiApp.onCreate()` 在主线程直接调用它）；
 * 2. 落在**同一触发时刻**的多条提醒合并成**同一个闹钟**，把该时刻的所有课程打包进
 *    Intent extras，触发后由 [ReminderReceiver] 各自发通知。这样每天实际的精确闹钟
 *    次数 = 提醒批次数，而不是课程条数 —— 这是绕开 Android 派发频率节流的关键；
 * 3. 排程覆盖未来 [HORIZON_DAYS] 天内的**全部**命中时刻（不是每门课只排最近一次），
 *    并且每次提醒触发时都会检查排程余量、不足 [ROLLING_REFRESH_DAYS] 天就自动整体
 *    重排（见 [refreshHorizonIfNeeded]），因此长期不打开 App 也不会断档；
 * 4. 每类批次每天最早的那一批课前提醒改用 `setAlarmClock`（不受 Doze 限制）；
 * 5. 没有「闹钟和提醒」特殊权限时降级为 `setAndAllowWhileIdle`，**不崩溃**；
 * 6. 单次排程的闹钟批次数硬性不超过 [MAX_ALARM_BATCHES]，超出按时间先后截断。
 */
object ReminderScheduler {

    /**
     * 串行化「重排 / 取消」这两类会改写闹钟与本地记录的操作。
     *
     * 提醒触发时的滚动续期重排与 App 启动时的重排可能并发，不加锁会让两次操作交错写坏
     * `scheduled_alarms` 记录（记录与实际闹钟不一致 → 部分闹钟变成无法取消的孤儿）。
     * Mutex 不可重入，因此加锁只做在**最外层入口**，内部一律调用 `...Locked` 版本。
     */
    private val rescheduleLock = Mutex()

    /**
     * 清空并重排全部提醒。开机、打开 App、数据变更后都应调用。
     *
     * 立即返回：读数据库与排闹钟都在后台线程完成。
     */
    fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            rescheduleAllNow(appContext)
        }
    }

    /** 取消全部提醒（清空课表时）。同样立即返回。 */
    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            rescheduleLock.withLock {
                cancelAllNowLocked(appContext)
            }
        }
    }

    /**
     * 重排的实际实现（挂起版）：供 [BootReceiver] 与 [ReminderReceiver] 在 goAsync()
     * 的异步窗口内等待完成。读库失败时保留旧闹钟，避免「提醒全丢」。
     *
     * 全程持有 [rescheduleLock]，与 [cancelAll]、其他重排请求互斥。
     */
    internal suspend fun rescheduleAllNow(context: Context) {
        rescheduleLock.withLock {
            val groups = try {
                planTriggers(context)
            } catch (t: Throwable) {
                // 读库失败：不清旧闹钟，宁可留着旧的也不要一条提醒都没有
                return
            }

            cancelAllNowLocked(context)
            groups.forEach { group -> scheduleGroup(context, group) }
            NotifyPrefs.writeScheduledAlarms(context, groups.map { alarmKey(it) }.toSet())
        }
    }

    /**
     * 取消全部已排闹钟（依据本地记录精确取消，不误伤系统闹钟）。
     * 调用方必须已持有 [rescheduleLock]。
     */
    private fun cancelAllNowLocked(context: Context) {
        NotifyPrefs.readScheduledAlarms(context).forEach { key ->
            val action = actionOfKey(key) ?: return@forEach
            val triggerAt = triggerAtOfKey(key) ?: return@forEach
            cancelAlarm(context, action, triggerAt)
        }
        NotifyPrefs.writeScheduledAlarms(context, emptySet())
    }

    /** 「稍后提醒」：把一次提醒整体推迟 [SNOOZE_MINUTES] 分钟再响 */
    internal fun scheduleSnoozeNow(
        context: Context,
        courseName: String,
        location: String,
        startAtMillis: Long
    ) {
        val triggerAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val group = TriggerGroup(
            triggerAt = triggerAt,
            isStart = false,
            isDayFirstRemind = false,
            items = listOf(
                TriggerItem(
                    courseName = courseName,
                    location = location,
                    startAt = startAtMillis,
                    dayIndex = 0,
                    dayTotal = 0
                )
            )
        )
        scheduleGroup(context, group)
        NotifyPrefs.writeScheduledAlarms(
            context,
            NotifyPrefs.readScheduledAlarms(context) + alarmKey(group)
        )
    }

    /**
     * 滚动续期：提醒触发时顺手检查「最后一个待触发的闹钟」还有多远。
     *
     * 剩余不足 [ROLLING_REFRESH_DAYS] 天就整体重排一次。这样只要课程还在上（用户还在用
     * 手机、提醒还在触发），排程就会自动往前滚动 —— 即使连续多天不打开 App 也不会断档。
     * 没有任何排程记录时直接返回（说明课表为空，没有可续期的提醒）。
     */
    internal suspend fun refreshHorizonIfNeeded(context: Context) {
        val latestTriggerAt = NotifyPrefs.readScheduledAlarms(context)
            .mapNotNull { triggerAtOfKey(it) }
            .maxOrNull() ?: return

        val threshold = System.currentTimeMillis() + ROLLING_REFRESH_DAYS * DAY_MILLIS
        if (latestTriggerAt < threshold) {
            rescheduleAllNow(context)
        }
    }

    // ---- 排程计划 ----

    /**
     * 计算未来 [HORIZON_DAYS] 天内**全部**该响的时刻，并按触发时刻合并成批次。
     *
     * 关键实现差异（修复「每门课只响一次」）：这里按**日期**逐天展开，而不是对每门课
     * 只找「下一次」——后者会让每门课在视界内只排一次，几天后核心提醒就静默失效。
     *
     * 每天产出两类批次：
     * - 上课时刻（startAt）：发「上课进行中」；
     * - 课前 [LEAD_MINUTES] 分钟：发「即将上课」倒计时。
     *
     * suspend：内部会读数据库（一次性读全量时段与课程）。
     */
    private suspend fun planTriggers(context: Context): List<TriggerGroup> {
        val repository = CourseRepository.get(context)
        val sessions = repository.allSessions()
        if (sessions.isEmpty()) return emptyList()
        val courses = repository.courses.first()

        val zone = ZoneId.systemDefault()
        val termStart = NotifyPrefs.termStartDate(context, zone)
        val now = System.currentTimeMillis()
        val nameById: Map<Long, CourseEntity> = courses.associateBy { it.id }

        // 同一天只算一次「当天时段表」，顺便得到进行中通知要用的当天序号与总数
        val dayCache = HashMap<LocalDate, List<SessionEntity>>()
        fun daySessionsOf(date: LocalDate): List<SessionEntity> = dayCache.getOrPut(date) {
            val week = NotifyPrefs.weekOf(termStart, date)
            if (week < 1 || week > WeekPatterns.MAX_WEEKS) {
                emptyList<SessionEntity>()
            } else {
                sessions
                    .filter {
                        it.dayOfWeek == date.dayOfWeek.value &&
                            WeekPatterns.contains(it.weekPattern, week)
                    }
                    .sortedBy { it.startSection }
            }
        }

        // 触发时刻 → 该时刻要处理的课程（同一个 key 就是「合并为同一个闹钟」）
        val remindBuckets = LinkedHashMap<Long, MutableList<TriggerItem>>()
        val startBuckets = LinkedHashMap<Long, MutableList<TriggerItem>>()
        // 每天最早的课前批次：升级为 setAlarmClock，并顺带刷新一次课表总览
        val firstRemindOfDay = HashMap<LocalDate, Long>()

        val today = LocalDate.now(zone)
        for (offset in 0 until HORIZON_DAYS) {
            val date = today.plusDays(offset.toLong())
            val daySessions = daySessionsOf(date)
            if (daySessions.isEmpty()) continue
            val dayTotal = daySessions.size

            for (index in daySessions.indices) {
                val session = daySessions[index]
                val course = nameById[session.courseId] ?: continue
                val startAt = date
                    .atTime(SectionTimes.startOf(session.startSection))
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
                val item = TriggerItem(course.name, session.location, startAt, index, dayTotal)

                // 上课时刻 → 「上课进行中」进度通知
                if (startAt > now) {
                    startBuckets.getOrPut(startAt) { mutableListOf() }.add(item)
                }

                // 课前 LEAD_MINUTES 分钟 → 倒计时提醒
                val remindAt = startAt - LEAD_MINUTES * 60_000L
                if (remindAt > now) {
                    remindBuckets.getOrPut(remindAt) { mutableListOf() }.add(item)
                    val first = firstRemindOfDay[date]
                    if (first == null || remindAt < first) firstRemindOfDay[date] = remindAt
                }
            }
        }

        val groups = ArrayList<TriggerGroup>(remindBuckets.size + startBuckets.size)

        remindBuckets.entries.sortedBy { it.key }.forEach { entry ->
            val day = dayOf(entry.value.first().startAt, zone)
            groups += TriggerGroup(
                triggerAt = entry.key,
                isStart = false,
                isDayFirstRemind = firstRemindOfDay[day] == entry.key,
                items = entry.value
            )
        }
        startBuckets.entries.sortedBy { it.key }.forEach { entry ->
            groups += TriggerGroup(
                triggerAt = entry.key,
                isStart = true,
                isDayFirstRemind = false,
                items = entry.value
            )
        }

        // 安全阀：按时间先后保留最近的批次，避免极端课表把 AlarmManager 塞爆
        return groups.sortedBy { it.triggerAt }.take(MAX_ALARM_BATCHES)
    }

    // ---- 闹钟写入 / 取消 ----

    private fun scheduleGroup(context: Context, group: TriggerGroup) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val operation = buildAlarmPendingIntent(context, group)
        val triggerAt = group.triggerAt

        try {
            when {
                // 没有精确闹钟权限：降级为不精确闹钟，宁可晚一点也绝不崩溃
                !canScheduleExactAlarms(context) ->
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)

                // 只有「每天第一批课前提醒」升级为 setAlarmClock（不受 Doze 限制）。
                // 上课时刻的批次不走这条，避免状态栏堆积闹钟图标。
                !group.isStart && group.isDayFirstRemind ->
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAt, clockShowIntent(context)),
                        operation
                    )

                else ->
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (t: Throwable) {
            // 权限在运行中被回收等极端情况：再降一级，绝不把异常抛给调用方
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } catch (ignored: Throwable) {
                // 实在排不上就放弃这一条
            }
        }
    }

    private fun cancelAlarm(context: Context, action: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val operation = PendingIntent.getBroadcast(
            context,
            requestCodeOf(triggerAtMillis),
            alarmIntent(context, action, triggerAtMillis),
            PENDING_INTENT_FLAGS
        )
        alarmManager.cancel(operation)
        operation.cancel()
    }

    /**
     * 闹钟 Intent 的「身份」只由 action + data 决定（extras 不参与 PendingIntent 相等判断），
     * 所以排程与取消可以复用同一个构造函数，取消时不必还原 extras。
     */
    private fun alarmIntent(context: Context, action: String, triggerAtMillis: Long): Intent {
        val intent = Intent(context, ReminderReceiver::class.java)
        intent.action = action
        intent.data = Uri.parse("kobi://reminder/$action/$triggerAtMillis")
        return intent
    }

    private fun buildAlarmPendingIntent(context: Context, group: TriggerGroup): PendingIntent {
        val intent = alarmIntent(context, actionOf(group), group.triggerAt)

        if (group.isStart) {
            // 上课时刻：逐条发「上课进行中」，需要课程名 + 地点 + 当天序号 + 当天总数
            intent.putStringArrayListExtra(
                ReminderReceiver.EXTRA_ONGOING_COURSE_NAMES,
                ArrayList(group.items.map { it.courseName })
            )
            intent.putStringArrayListExtra(
                ReminderReceiver.EXTRA_ONGOING_LOCATIONS,
                ArrayList(group.items.map { it.location })
            )
            intent.putExtra(
                ReminderReceiver.EXTRA_ONGOING_INDEXES,
                group.items.map { it.dayIndex }.toIntArray()
            )
            intent.putExtra(
                ReminderReceiver.EXTRA_ONGOING_TOTALS,
                group.items.map { it.dayTotal }.toIntArray()
            )
        } else {
            // 课前提醒：逐条发倒计时通知
            intent.putStringArrayListExtra(
                ReminderReceiver.EXTRA_COURSE_NAMES,
                ArrayList(group.items.map { it.courseName })
            )
            intent.putStringArrayListExtra(
                ReminderReceiver.EXTRA_LOCATIONS,
                ArrayList(group.items.map { it.location })
            )
            intent.putExtra(
                ReminderReceiver.EXTRA_START_ATS,
                group.items.map { it.startAt }.toLongArray()
            )
            // 每天第一批课前提醒顺带刷新一次课表总览
            if (group.isDayFirstRemind) {
                intent.putExtra(ReminderReceiver.EXTRA_SHOW_OVERVIEW, true)
            }
        }

        return PendingIntent.getBroadcast(
            context,
            requestCodeOf(group.triggerAt),
            intent,
            PENDING_INTENT_FLAGS
        )
    }

    /** 点击系统闹钟图标时打开应用 */
    private fun clockShowIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(context, REQUEST_CODE_CLOCK, intent, PENDING_INTENT_FLAGS)
    }

    private fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: false
        } catch (t: Throwable) {
            false
        }
    }

    private fun actionOf(group: TriggerGroup): String =
        if (group.isStart) ReminderReceiver.ACTION_START else ReminderReceiver.ACTION_REMIND

    /** 本地记录的键：`action|triggerAtMillis`，两类闹钟可共存且能精确取消 */
    private fun alarmKey(group: TriggerGroup): String =
        alarmKey(actionOf(group), group.triggerAt)

    private fun alarmKey(action: String, triggerAtMillis: Long): String = "$action|$triggerAtMillis"

    private fun actionOfKey(key: String): String? {
        val separator = key.indexOf('|')
        if (separator <= 0) return null
        return key.substring(0, separator)
    }

    private fun triggerAtOfKey(key: String): Long? {
        val separator = key.indexOf('|')
        if (separator <= 0) return null
        return key.substring(separator + 1).toLongOrNull()
    }

    private fun dayOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun requestCodeOf(triggerAtMillis: Long): Int = (triggerAtMillis / 60_000L).toInt()

    companion object {

        /** 提前量（分钟），默认 60 */
        const val LEAD_MINUTES: Int = 60

        /** 「稍后提醒」推迟时长（分钟） */
        private const val SNOOZE_MINUTES: Long = 10L

        private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

        /**
         * 排程视界：排未来这么多天内的**全部**提醒。
         *
         * 为什么取 7 天：现在每门课每天会产生「课前提醒 + 上课开始」两批闹钟，批次数是原来的
         * 两倍，而批次数还要受 [MAX_ALARM_BATCHES] 约束。7 天 × 典型每天 4 个时段 × 2 类
         * ≈ 56 批，既不触发截断，又能覆盖完整一周。
         * 视界变短不会导致断档：任何一次提醒触发都会调用 [refreshHorizonIfNeeded] 自动续期。
         */
        private const val HORIZON_DAYS: Int = 7

        /** 单次排程最多排出多少个闹钟批次；超出按时间先后截断（安全阀，不是常态） */
        private const val MAX_ALARM_BATCHES: Int = 60

        /** 剩余排程不足这么多天时，在提醒触发时自动整体重排（滚动续期） */
        private const val ROLLING_REFRESH_DAYS: Long = 3L

        private const val REQUEST_CODE_CLOCK = 200

        /** API 31+ 必须声明 PendingIntent 可变性；本模块全部为不可变 */
        private val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}

/** 一条待处理记录：某门课在某个时刻要提醒 / 要开始上课 */
private data class TriggerItem(
    val courseName: String,
    val location: String,
    val startAt: Long,
    /** 该时段在「当天」时段表中的序号（0 基），供「上课进行中」通知使用 */
    val dayIndex: Int,
    /** 当天时段总数，供「上课进行中」通知使用 */
    val dayTotal: Int
)

/** 一个闹钟：触发时刻 + 类型（课前提醒 / 上课开始）+ 该时刻要处理的全部课程 */
private data class TriggerGroup(
    val triggerAt: Long,
    val isStart: Boolean,
    val isDayFirstRemind: Boolean,
    val items: List<TriggerItem>
)

/**
 * 节次 → 具体时钟时间。
 *
 * 依据**契约 8.7 节冻结的唯一作息表**（与 data 的 ICS 导入共用同一张表）：
 * 1–12 节起始时间为
 * `08:00 / 08:55 / 10:00 / 10:55 / 14:00 / 14:55 / 16:00 / 16:55 / 19:00 / 19:55 / 20:50 / 21:45`，
 * 每节 45 分钟上课 + 10 分钟课间，**相邻节次起始时间间隔 55 分钟**，超过 12 节按 55 分钟递推。
 * （注：契约 8.1 节残留的 `21:40` 系笔误，以 8.7 节表格的 21:45 为准。）
 */
internal object SectionTimes {

    private val STARTS: List<LocalTime> = listOf(
        LocalTime.of(8, 0),
        LocalTime.of(8, 55),
        LocalTime.of(10, 0),
        LocalTime.of(10, 55),
        LocalTime.of(14, 0),
        LocalTime.of(14, 55),
        LocalTime.of(16, 0),
        LocalTime.of(16, 55),
        LocalTime.of(19, 0),
        LocalTime.of(19, 55),
        LocalTime.of(20, 50),
        LocalTime.of(21, 45)
    )

    /** 相邻节次起始时间的间隔：45 分钟上课 + 10 分钟课间（契约 8.7） */
    private const val SECTION_INTERVAL_MINUTES: Long = 55L

    /** 单节课时长（契约 8.7） */
    private const val LESSON_MINUTES: Long = 45L

    /** 第 section 节（1-based）的开始时间；越界值被夹紧并按 55 分钟递推，不会抛异常 */
    fun startOf(section: Int): LocalTime {
        val index = (section - 1).coerceAtLeast(0)
        if (index < STARTS.size) return STARTS[index]
        return STARTS.last().plusMinutes(SECTION_INTERVAL_MINUTES * (index - STARTS.size + 1))
    }

    /** 第 section 节的结束时间（开始 + 45 分钟），用于判断「当前正在上的是哪一节」 */
    fun endOf(section: Int): LocalTime = startOf(section).plusMinutes(LESSON_MINUTES)
}

/**
 * 本模块自己的本地配置与排程记录。
 *
 * 使用 SharedPreferences（契约明确禁止 DataStore）。其中：
 * - `term_start_millis` 是**学期第 1 周的周一 00:00**（由设置页写入，见契约 8.6）；
 *   未配置时回退为「本周周一」，即把当前这一周当作第 1 周，保证功能可用而不是不响；
 * - `scheduled_alarms` 记录本应用排出去的闹钟，形如 `action|triggerAtMillis`，
 *   用于精确取消（系统不提供「枚举本应用闹钟」的接口）。
 */
internal object NotifyPrefs {

    const val PREFS_NAME: String = "kobi_settings"

    /** 学期第 1 周周一的 00:00 对应的毫秒时间戳；设置页负责写入 */
    const val KEY_TERM_START_MILLIS: String = "term_start_millis"

    private const val KEY_SCHEDULED_ALARMS = "scheduled_alarms"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 学期起始日（第 1 周周一）；未配置或数据异常时回退为本击周一 */
    fun termStartDate(context: Context, zone: ZoneId): LocalDate {
        val millis = prefs(context).getLong(KEY_TERM_START_MILLIS, 0L)
        if (millis > 0L) {
            return try {
                Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            } catch (t: Throwable) {
                thisWeekMonday(zone)
            }
        }
        return thisWeekMonday(zone)
    }

    /** 计算某个日期属于第几教学周（1-based）；早于学期起始返回 0 */
    fun weekOf(termStart: LocalDate, date: LocalDate): Int {
        if (date.isBefore(termStart)) return 0
        return (ChronoUnit.DAYS.between(termStart, date) / 7L).toInt() + 1
    }

    /** 读取已排闹钟记录（`action|triggerAtMillis`） */
    fun readScheduledAlarms(context: Context): Set<String> {
        val raw: Set<String>? = prefs(context)
            .getStringSet(KEY_SCHEDULED_ALARMS, emptySet<String>())
        return raw.orEmpty().toSet()
    }

    fun writeScheduledAlarms(context: Context, alarms: Set<String>) {
        val editor = prefs(context).edit()
        editor.putStringSet(KEY_SCHEDULED_ALARMS, alarms)
        editor.apply()
    }

    private fun thisWeekMonday(zone: ZoneId): LocalDate {
        val today = LocalDate.now(zone)
        return today.minusDays((today.dayOfWeek.value - 1).toLong())
    }
}
