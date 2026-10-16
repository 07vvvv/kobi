package com.xixi.timetable

import android.app.Application
import com.xixi.timetable.notify.NotificationChannels

/**
 * 应用入口。
 *
 * 冷启动窗口内**只**创建通知渠道（纯内存操作，且必须在任何通知发出前完成），
 * 不做任何数据库访问、不重排闹钟（契约 9.7 硬性要求）。
 *
 * 提醒重排的五条覆盖路径（保证「推迟」不等于「漏排」）：
 * 1. 冷启动：MainActivity 首帧之后触发一次（见 MainActivity.onCreate 的 decorView.post）。
 *    本次改动只是把这一步从 Application.onCreate 后移到首帧之后；触发事件仍然是「用户打开 App」，
 *    覆盖集合与原实现完全一致，被推迟的只是同一事件的处理时机。
 * 2. 自续期（最关键）：每次提醒触发时 ReminderReceiver 会调用
 *    ReminderScheduler.refreshHorizonIfNeeded，排程余量不足 ROLLING_REFRESH_DAYS 天即整体重排。
 *    该路径完全独立于「是否打开 App」，因此长期不打开 App 也不会断档。
 * 3. 设备重启：BootReceiver 收到 BOOT_COMPLETED。
 * 4. App 更新：BootReceiver 收到 MY_PACKAGE_REPLACED。
 * 5. 数据变更：MainViewModel 在课程增删改后调用 ReminderScheduler.rescheduleAll。
 *
 * 另：AlarmManager 已排定的闹钟由系统持有，进程被杀不会丢失，所以把重排推迟到首帧之后
 * 不会影响任何已排定的提醒。
 */
class KobiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
    }
}
