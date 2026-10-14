package com.xixi.timetable

import android.app.Application
import com.xixi.timetable.notify.NotificationChannels
import com.xixi.timetable.notify.ReminderScheduler

/**
 * 应用入口。
 *
 * 启动时做两件事：创建通知渠道、重排全部提醒。
 * 另外两处重排时机不在本类：开机广播（BootReceiver）、数据变更后（MainViewModel）。
 *
 * 注意：ReminderScheduler.rescheduleAll 会读数据库，其内部必须自行切到后台线程，
 * 本方法运行在主线程，不得在此处做任何阻塞式数据库访问。
 */
class KobiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        ReminderScheduler.rescheduleAll(this)
    }
}
