package com.xixi.timetable.notify

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 权限与系统限制的快照，供设置页展示。
 *
 * 全部字段都是「当前真实状态」，读取失败一律按「未满足」处理，绝不抛异常。
 */
data class PermissionState(
    val notificationsEnabled: Boolean,
    val exactAlarmAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val canUseFullScreenIntent: Boolean
)

/**
 * 权限自检与系统设置跳转。
 *
 * 国产 ROM 上这些设置页路径差异极大（甚至被裁掉），因此每个跳转都带兜底：
 * 打不开时退回应用详情页，让用户自己找。
 */
object PermissionAuditor {

    /** 读取当前权限状态快照 */
    fun audit(context: Context): PermissionState {
        val appContext = context.applicationContext
        return PermissionState(
            notificationsEnabled = notificationsEnabled(appContext),
            exactAlarmAllowed = exactAlarmAllowed(appContext),
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(appContext),
            backgroundRestricted = backgroundRestricted(appContext),
            canUseFullScreenIntent = canUseFullScreenIntent(appContext)
        )
    }

    /** 打开系统通知设置页 */
    fun openNotificationSettings(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        if (startSafely(appContext, intent)) return
        openAppDetailsSettings(appContext)
    }

    /** 打开「闹钟和提醒」特殊权限页；系统没有该页面时回退到应用详情页 */
    fun openExactAlarmSettings(context: Context) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            intent.data = Uri.fromParts("package", appContext.packageName, null)
            if (startSafely(appContext, intent)) return
        }
        openAppDetailsSettings(appContext)
    }

    /** 打开电池优化白名单页；打不开时回退到应用详情页 */
    fun openBatteryOptimizationSettings(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (startSafely(appContext, intent)) return
        openAppDetailsSettings(appContext)
    }

    /** 打开应用详情页（自启动引导用：小米等 ROM 的自启动开关无法程序化检测，只能引导用户自己找） */
    fun openAppDetailsSettings(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", appContext.packageName, null)
        startSafely(appContext, intent)
    }

    // ---- 内部实现 ----

    /** 统一起跳转入口：补上 NEW_TASK（调用方可能是 Application 上下文）并吞掉异常 */
    private fun startSafely(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.applicationContext.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun notificationsEnabled(context: Context): Boolean {
        return try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (t: Throwable) {
            false
        }
    }

    /** Android 12（API 31）起为特殊权限；更低版本无此概念，视为已允许 */
    private fun exactAlarmAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            manager?.canScheduleExactAlarms() ?: false
        } catch (t: Throwable) {
            false
        }
    }

    private fun ignoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    /** Android 9（API 28）起可查「后台受限」 */
    private fun backgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.isBackgroundRestricted ?: false
        } catch (t: Throwable) {
            false
        }
    }

    /** Android 14（API 34）起可查全屏 Intent 是否被允许（本项目不使用全屏 Intent，仅如实展示） */
    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.canUseFullScreenIntent() ?: false
        } catch (t: Throwable) {
            false
        }
    }
}
