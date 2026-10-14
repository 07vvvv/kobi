package com.xixi.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟触发 / 通知按钮的接收器。
 *
 * 广播的 onReceive 运行在主线程且受系统看门狗约束，**绝不能做耗时操作**：
 * 这里先 `goAsync()` 向系统申请一小段后台时间，再切到 IO 线程处理后 `finish()`。
 * 整个处理过程包在 try/catch 里，任何异常都不外抛（外抛会被系统判为应用崩溃）。
 *
 * 承载两类闹钟：
 * - [ACTION_REMIND]：课前 [ReminderScheduler.LEAD_MINUTES] 分钟，发「即将上课」倒计时；
 *   每天第一批还会顺带刷新一次课表总览，并在末尾做一次滚动续期；
 * - [ACTION_START]：上课时刻，发「上课进行中」。
 *
 * 本 Receiver 在 Manifest 中 exported=false，只有本应用能唤起它。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val action = intent.action ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_REMIND -> handleRemind(appContext, intent)
                    ACTION_START -> handleStart(appContext, intent)
                    ACTION_SNOOZE -> handleSnooze(appContext, intent)
                    ACTION_DISMISS -> handleDismiss(appContext, intent)
                    ACTION_RESCHEDULE -> ReminderScheduler.rescheduleAllNow(appContext)
                    else -> Unit
                }
            } catch (t: Throwable) {
                // 广播里任何异常都不能外抛
            } finally {
                try {
                    pendingResult.finish()
                } catch (ignored: Throwable) {
                    // 已经 finish 过则忽略
                }
            }
        }
    }

    /**
     * 课前提醒到点：同一个闹钟里可能打包了**同一触发时刻的多条提醒**，这里逐条发通知。
     * 三条数组由调度端一起写入，因此按最小长度循环，避免脏数据造成越界。
     */
    private suspend fun handleRemind(context: Context, intent: Intent) {
        val names = intent.getStringArrayListExtra(EXTRA_COURSE_NAMES) ?: return
        val locations = intent.getStringArrayListExtra(EXTRA_LOCATIONS) ?: return
        val startAts = intent.getLongArrayExtra(EXTRA_START_ATS) ?: return

        val count = minOf(names.size, locations.size, startAts.size)
        for (index in 0 until count) {
            Notifier.showCountdownReminder(
                context = context,
                courseName = names[index],
                location = locations[index],
                startAtMillis = startAts[index]
            )
        }

        // 每天第一批课前提醒：顺带把课表总览刷新一次（这样总览通知每天都是新的）
        // 用挂起版并等待它完成，避免广播提前 finish() 导致总览丢失
        if (intent.getBooleanExtra(EXTRA_SHOW_OVERVIEW, false)) {
            Notifier.showTimetableOverviewNow(context)
        }

        // 滚动续期：排程快用完了就整体重排，避免「长期不打开 App 就断档」
        ReminderScheduler.refreshHorizonIfNeeded(context)
    }

    /**
     * 上课时刻到点：同一闹钟里可能打包了同时开始的多门课，逐条发「上课进行中」。
     * [EXTRA_ONGOING_INDEXES] 是「当天序号（0 基）」，[EXTRA_ONGOING_TOTALS] 是当天总时段数。
     */
    private fun handleStart(context: Context, intent: Intent) {
        val names = intent.getStringArrayListExtra(EXTRA_ONGOING_COURSE_NAMES) ?: return
        val locations = intent.getStringArrayListExtra(EXTRA_ONGOING_LOCATIONS) ?: return
        val indexes = intent.getIntArrayExtra(EXTRA_ONGOING_INDEXES) ?: return
        val totals = intent.getIntArrayExtra(EXTRA_ONGOING_TOTALS) ?: return

        val count = minOf(names.size, locations.size, indexes.size, totals.size)
        for (index in 0 until count) {
            Notifier.showProgressOngoing(
                context = context,
                courseName = names[index],
                location = locations[index],
                currentIndex = indexes[index],
                totalCount = totals[index]
            )
        }
    }

    /** 「稍后提醒」：撤掉当前通知，另排一个 10 分钟后的闹钟 */
    private fun handleSnooze(context: Context, intent: Intent) {
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: return
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val startAtMillis = intent.getLongExtra(EXTRA_START_AT, 0L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        Notifier.cancel(context, notificationId)
        ReminderScheduler.scheduleSnoozeNow(context, courseName, location, startAtMillis)
    }

    /** 「知道了」/「结束」：只撤掉这一条通知 */
    private fun handleDismiss(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        Notifier.cancel(context, notificationId)
    }

    companion object {

        // 以下常量只在模块内使用（标记 internal，不扩大对外公开 API）

        /** 课前提醒到点 */
        internal const val ACTION_REMIND: String = "com.xixi.timetable.notify.action.REMIND"

        /** 上课时刻到点（发「上课进行中」） */
        internal const val ACTION_START: String = "com.xixi.timetable.notify.action.START"

        /** 通知按钮：稍后提醒 */
        internal const val ACTION_SNOOZE: String = "com.xixi.timetable.notify.action.SNOOZE"

        /** 通知按钮：知道了 / 结束 */
        internal const val ACTION_DISMISS: String = "com.xixi.timetable.notify.action.DISMISS"

        /** 通知按钮：立即重排提醒 */
        internal const val ACTION_RESCHEDULE: String = "com.xixi.timetable.notify.action.RESCHEDULE"

        /** 课前提醒批次的批量数据（与 EXTRA_* 单数项区分开） */
        internal const val EXTRA_COURSE_NAMES: String = "extra_course_names"
        internal const val EXTRA_LOCATIONS: String = "extra_locations"
        internal const val EXTRA_START_ATS: String = "extra_start_ats"

        /** 上课时刻批次的批量数据 */
        internal const val EXTRA_ONGOING_COURSE_NAMES: String = "extra_ongoing_course_names"
        internal const val EXTRA_ONGOING_LOCATIONS: String = "extra_ongoing_locations"
        internal const val EXTRA_ONGOING_INDEXES: String = "extra_ongoing_indexes"
        internal const val EXTRA_ONGOING_TOTALS: String = "extra_ongoing_totals"

        /** 该批次是否顺带刷新课表总览（每天第一批课前提醒为 true） */
        internal const val EXTRA_SHOW_OVERVIEW: String = "extra_show_overview"

        /** 单条提醒的数据（稍后提醒要用它重建通知） */
        internal const val EXTRA_COURSE_NAME: String = "extra_course_name"
        internal const val EXTRA_LOCATION: String = "extra_location"
        internal const val EXTRA_START_AT: String = "extra_start_at"
        internal const val EXTRA_NOTIFICATION_ID: String = "extra_notification_id"
    }
}
