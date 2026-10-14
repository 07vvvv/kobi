package com.xixi.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后重排闹钟。
 *
 * 闹钟不会跨重启存活，因此这是「课前提醒」能长期生效的关键一环。
 *
 * 两点必须注意：
 * 1. 本 Receiver 在 Manifest 中 `exported=true`（系统广播需要），所以**必须白名单校验
 *    action**，否则任意应用都能用显式 Intent 触发它；
 * 2. 重排要读数据库，属于耗时操作，广播里不能直接做 —— 用 `goAsync()` + IO 线程，
 *    处理完立即 `finish()`。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val accepted = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_QUICKBOOT_POWERON ||
            action == ACTION_HTC_QUICKBOOT_POWERON
        if (!accepted) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 渠道随应用安装持久化，这里只是幂等补齐（重复调用无副作用）
                NotificationChannels.ensure(appContext)
                // 内部会先读数据库再重排；读失败时保留旧闹钟，不抛异常
                ReminderScheduler.rescheduleAllNow(appContext)
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
}

/** 部分 ROM（HTC、联想等）在「快速开机」时发的私有重启广播，无法写进 Manifest filter，只能在这里白名单放行 */
private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
