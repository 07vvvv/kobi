package com.xixi.timetable.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xixi.timetable.MainActivity
import com.xixi.timetable.R
import com.xixi.timetable.data.CourseRepository
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.week.WeekPatterns
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 通知构建与发送。
 *
 * 设计要点：
 * 1. 每条通知的操作按钮**不超过 3 个**（Android 硬限制），且不与「点击通知体」的行为重复；
 * 2. 倒计时一律交给系统时钟：`setUsesChronometer(true)` + `setChronometerCountDown(true)`
 *    + `setWhen(startAt)`，**绝不**自己起定时器每秒刷新通知（系统会限流并丢弃高频更新）；
 * 3. 发送前统一走 [canPost] 自检，通知权限未授予时静默返回，绝不抛异常。
 */
object Notifier {

    /**
     * 课前提醒：动态倒计时。
     *
     * 通知 id 由 (startAtMillis, courseName) 派生，因此同一触发时刻的多门课各自拥有一条通知，
     * 同一门课重复发送时只会原地更新。
     */
    fun showCountdownReminder(
        context: Context,
        courseName: String,
        location: String,
        startAtMillis: Long
    ) {
        val appContext = context.applicationContext
        if (!canPost(appContext)) return

        val notificationId = NotificationIds.forReminder(startAtMillis, courseName)
        val identity = NotificationIds.identity(startAtMillis, courseName)
        val timeText = TIME_FORMATTER.format(
            Instant.ofEpochMilli(startAtMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        )
        val where = if (location.isBlank()) "" else " · $location"

        val snoozeIntent = actionIntent(
            context = appContext,
            action = ReminderReceiver.ACTION_SNOOZE,
            identity = identity
        ) { intent ->
            intent.putExtra(ReminderReceiver.EXTRA_COURSE_NAME, courseName)
            intent.putExtra(ReminderReceiver.EXTRA_LOCATION, location)
            intent.putExtra(ReminderReceiver.EXTRA_START_AT, startAtMillis)
            intent.putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val dismissIntent = actionIntent(
            context = appContext,
            action = ReminderReceiver.ACTION_DISMISS,
            identity = identity
        ) { intent ->
            intent.putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }

        val notification = NotificationCompat.Builder(appContext, NotificationChannels.CHANNEL_REMINDER)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle("即将上课：$courseName")
            .setContentText("$timeText 上课$where")
            .setWhen(startAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "距离 $timeText 上课不到 ${ReminderScheduler.LEAD_MINUTES} 分钟。\n$courseName$where"
                )
            )
            .setContentIntent(openAppIntent(appContext))
            .addAction(android.R.drawable.ic_popup_sync, "稍后提醒", snoozeIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "知道了", dismissIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        post(appContext, notificationId, notification)
    }

    /**
     * 上课进行中：分段进度样式。
     *
     * [currentIndex] 采用 0 基（第 1 节传 0），仅用于进度条与文案；越界值会被夹紧，不会崩溃。
     */
    fun showProgressOngoing(
        context: Context,
        courseName: String,
        location: String,
        currentIndex: Int,
        totalCount: Int
    ) {
        val appContext = context.applicationContext
        if (!canPost(appContext)) return

        val safeTotal = totalCount.coerceAtLeast(1)
        val safeIndex = currentIndex.coerceIn(0, safeTotal)
        val where = if (location.isBlank()) "" else " · $location"

        val dismissIntent = actionIntent(
            context = appContext,
            action = ReminderReceiver.ACTION_DISMISS,
            identity = IDENTITY_ONGOING
        ) { intent ->
            intent.putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, NotificationIds.ONGOING)
        }

        val notification = NotificationCompat.Builder(appContext, NotificationChannels.CHANNEL_ONGOING)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle("正在上课：$courseName")
            .setContentText("第 ${safeIndex + 1} / $safeTotal 节$where")
            .setProgress(safeTotal, safeIndex, false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(appContext))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束", dismissIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        post(appContext, NotificationIds.ONGOING, notification)
    }

    /**
     * 课表总览：自定义布局（RemoteViews）。
     *
     * 使用 `DecoratedCustomViewStyle` + 收起/展开两套 RemoteViews，布局由 Lead 提供
     * （`res/layout/notification_timetable.xml`，可填充 id：nt_title / nt_subtitle / nt_day1..nt_day5）。
     * 构造 RemoteViews 时传的包名必须是**持有该布局的包**（即本应用），否则跨进程 inflate 会失败。
     *
     * RemoteViews 只支持有限控件与方法，因此这里仅使用 setTextViewText。
     * 读库是阻塞操作，本方法立即返回，真正的查询在 IO 线程完成。
     */
    fun showTimetableOverview(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            showTimetableOverviewNow(appContext)
        }
    }

    /**
     * 挂起版总览刷新：供 [ReminderReceiver] 在 goAsync() 的异步窗口内**等待完成**。
     *
     * 为什么需要它：public 版是「即发即忘」的，广播若在它完成前就 finish()，进程优先级下降，
     * 总览通知可能丢失 —— 而「每天更新一次总览」恰恰是靠广播批次触发的。
     */
    internal suspend fun showTimetableOverviewNow(context: Context) {
        try {
            if (!canPost(context)) return

            val repository = CourseRepository.get(context)
            val sessions = repository.allSessions()
            val courses = repository.courses.first()

            val zone = ZoneId.systemDefault()
            val termStart = NotifyPrefs.termStartDate(context, zone)
            val today = LocalDate.now(zone)
            val nameById = courses.associateBy { it.id }

            // 周次越界（学期尚未开始等）时夹到 1..MAX_WEEKS，避免出现「第 0 周」
            val week = NotifyPrefs.weekOf(termStart, today).coerceIn(1, WeekPatterns.MAX_WEEKS)
            val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())

            // 周一~周五格子：本周该日的时段数量
            val weekdayCounts: List<Int> = (0..4).map { offset ->
                val dayValue = monday.plusDays(offset.toLong()).dayOfWeek.value
                sessions.count {
                    it.dayOfWeek == dayValue && WeekPatterns.contains(it.weekPattern, week)
                }
            }

            val title = "本周课表 · 第 $week 周"
            val subtitle = buildSubtitle(sessions, nameById, termStart, zone)

            val refreshIntent = actionIntent(
                context = context,
                action = ReminderReceiver.ACTION_RESCHEDULE,
                identity = IDENTITY_OVERVIEW
            ) { _ -> }

            val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ONGOING)
                .setSmallIcon(SMALL_ICON)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                // 收起态：格子用单行紧凑写法，避免被系统裁掉
                .setCustomContentView(
                    timetableRemoteViews(context, title, subtitle, weekdayCounts, twoLineCells = false)
                )
                // 展开态：格子按契约示例用两行文本「一\n3 节」
                .setCustomBigContentView(
                    timetableRemoteViews(context, title, subtitle, weekdayCounts, twoLineCells = true)
                )
                .setContentIntent(openAppIntent(context))
                .addAction(SMALL_ICON, "重排提醒", refreshIntent)
                .setAutoCancel(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            post(context, NotificationIds.OVERVIEW, notification)
        } catch (t: Throwable) {
            // 读库失败、RemoteViews 填充异常等都不该让调用方崩溃，静默放弃即可
        }
    }

    /** 取消某条通知；id 非法时直接忽略 */
    fun cancel(context: Context, notificationId: Int) {
        if (notificationId <= 0) return
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(notificationId)
        } catch (t: Throwable) {
            // 通知服务不可用时忽略
        }
    }


    /**
     * 通知栏小图标：单色矢量（`@drawable/ic_notification`）。
     *
     * 不能用 `const val` —— AGP 8 起 R 字段默认不是编译期常量（nonFinalResIds）。
     */
    private val SMALL_ICON = R.drawable.ic_notification

    /** 夜间模式下总览标题的浅色（res 未提供 layout-night 变体，故在运行期兜住可读性） */
    private val COLOR_NIGHT_TITLE = 0xFFF1F3F4.toInt()

    /** 夜间模式下总览副标题的浅灰 */
    private val COLOR_NIGHT_SUBTITLE = 0xFFBDC1C6.toInt()

    private const val IDENTITY_ONGOING = "ongoing"
    private const val IDENTITY_OVERVIEW = "overview"

    private const val REQUEST_CODE_OPEN_APP = 100
    private const val ACTION_DATA_PREFIX = "kobi://reminder/action"

    /** API 31+ 起 PendingIntent 必须声明可变性；本应用的所有 PendingIntent 都不需要被改写 */
    private val PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 发送前的自检：通知权限未授予时静默跳过，绝不抛异常 */
    fun canPost(context: Context): Boolean {
        return try {
            NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()
        } catch (t: Throwable) {
            false
        }
    }

    /** 点击通知体：打开应用（所有通知共用同一个 PendingIntent） */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(context, REQUEST_CODE_OPEN_APP, intent, PENDING_INTENT_FLAGS)
    }

    /**
     * 构造操作按钮的广播 PendingIntent。
     *
     * 注意：PendingIntent 的「相等」只看 requestCode + action/data/type/class/categories，
     * **不看 extras**。所以必须用 data 里的 [identity] 把不同课程的按钮区分开，
     * 否则同一时刻的多门课会共用同一个 PendingIntent，点击时串数据。
     */
    private fun actionIntent(
        context: Context,
        action: String,
        identity: String,
        extras: (Intent) -> Unit
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        intent.action = action
        intent.data = Uri.parse("$ACTION_DATA_PREFIX/$action/$identity")
        extras(intent)
        return PendingIntent.getBroadcast(context, identity.hashCode(), intent, PENDING_INTENT_FLAGS)
    }

    /**
     * 统一的发送出口：再自检一次权限，并吞掉极端情况下的异常。
     *
     * 已由 [canPost]（areNotificationsEnabled）前置守卫；这里抑制 Lint 的 MissingPermission
     * 误报 —— Lint 不总能识别自定义的守卫函数。
     */
    @SuppressLint("MissingPermission")
    private fun post(context: Context, notificationId: Int, notification: Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (t: Throwable) {
            // 权限在发送瞬间被撤销、通知服务被系统限制等情况：静默失败，绝不崩溃
        }
    }

    /**
     * 填充总览通知的自定义布局。
     *
     * RemoteViews 支持的方法有限，这里只用 setTextViewText；
     * 收起态用单行「一 3 节」，展开态用契约示例的两行「一\n3 节」。
     */
    private fun timetableRemoteViews(
        context: Context,
        title: String,
        subtitle: String,
        weekdayCounts: List<Int>,
        twoLineCells: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_timetable)
        views.setTextViewText(R.id.nt_title, title)
        views.setTextViewText(R.id.nt_subtitle, subtitle)

        // 布局里 title/subtitle 的文字颜色是按浅色底硬编码的深色。夜间模式下系统通知底色是深色，
        // 深字会几乎不可见，因此这两行在夜间改成浅色；星期格子自带浅色底，保持深字即可。
        if (isNightMode(context)) {
            views.setTextColor(R.id.nt_title, COLOR_NIGHT_TITLE)
            views.setTextColor(R.id.nt_subtitle, COLOR_NIGHT_SUBTITLE)
        }

        val dayIds = intArrayOf(
            R.id.nt_day1,
            R.id.nt_day2,
            R.id.nt_day3,
            R.id.nt_day4,
            R.id.nt_day5
        )
        val dayLabels = arrayOf("一", "二", "三", "四", "五")
        for (index in dayIds.indices) {
            val count = weekdayCounts.getOrNull(index) ?: 0
            val amount = if (count > 0) "$count 节" else "—"
            val text = if (twoLineCells) {
                "${dayLabels[index]}\n$amount"
            } else {
                "${dayLabels[index]} $amount"
            }
            views.setTextViewText(dayIds[index], text)
        }
        return views
    }

    /** 当前是否处于系统深色模式（用于给自定义布局里没有背景色的两行文字选颜色） */
    private fun isNightMode(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 副标题：优先给「当前」——今天第一个尚未结束的时段（正在上或马上要上）；
     * 今天没课则给明天的第一节；两天都没课则如实说明。
     */
    private fun buildSubtitle(
        sessions: List<SessionEntity>,
        nameById: Map<Long, CourseEntity>,
        termStart: LocalDate,
        zone: ZoneId
    ): String {
        val today = LocalDate.now(zone)
        val todaySessions = sessionsOfDay(sessions, today, termStart)
        if (todaySessions.isNotEmpty()) {
            val now = LocalTime.now(zone)
            // 「当前」= 今天第一个尚未结束的时段（正在上或马上要上）
            val upcoming = todaySessions.firstOrNull { SectionTimes.endOf(it.endSection) > now }
            if (upcoming != null) return "当前：" + describe(upcoming, nameById)
            // 今天的课都上完了，就不再谎称「当前」，改为提示明天第一节
            val first = sessionsOfDay(sessions, today.plusDays(1), termStart).firstOrNull()
                ?: return "今天的课已结束"
            return "今天已结束 · 明天：" + describe(first, nameById)
        }
        val tomorrowSessions = sessionsOfDay(sessions, today.plusDays(1), termStart)
        val first = tomorrowSessions.firstOrNull() ?: return "今天与明天都没有课"
        return "明天：" + describe(first, nameById)
    }

    /** 取某个日期在对应教学周内、按开始节次升序排列的时段 */
    private fun sessionsOfDay(
        sessions: List<SessionEntity>,
        date: LocalDate,
        termStart: LocalDate
    ): List<SessionEntity> {
        val week = NotifyPrefs.weekOf(termStart, date)
        if (week < 1 || week > WeekPatterns.MAX_WEEKS) return emptyList()
        return sessions
            .filter {
                it.dayOfWeek == date.dayOfWeek.value &&
                    WeekPatterns.contains(it.weekPattern, week)
            }
            .sortedBy { it.startSection }
    }

    /** 「课程名 · 地点」；地点为空时只留课程名 */
    private fun describe(session: SessionEntity, nameById: Map<Long, CourseEntity>): String {
        val name = nameById[session.courseId]?.name ?: "未命名课程"
        return if (session.location.isBlank()) name else "$name · ${session.location}"
    }
}

/**
 * 通知 id 分配规则。刻意集中在一处，保证发送端与取消端完全一致。
 *
 * - 1：课表总览
 * - 2：上课进行中
 * - 1000 起：每条课前提醒（由课程开始时刻 + 课程名派生，稳定且可重复覆盖）
 */
private object NotificationIds {

    const val OVERVIEW: Int = 1
    const val ONGOING: Int = 2

    private const val REMINDER_BASE = 1000
    private const val REMINDER_SLOT = 100_000

    /** 课前提醒的身份标识：同一时刻 + 同一课程名视为同一条提醒 */
    fun identity(startAtMillis: Long, courseName: String): String = "$startAtMillis#$courseName"

    fun forReminder(startAtMillis: Long, courseName: String): Int {
        val hash = identity(startAtMillis, courseName).hashCode() and 0x7FFFFFFF
        return REMINDER_BASE + hash % REMINDER_SLOT
    }
}
