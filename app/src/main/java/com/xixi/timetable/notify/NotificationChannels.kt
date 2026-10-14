package com.xixi.timetable.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings

/**
 * 通知渠道集中定义。
 *
 * Android 8.0（API 26）起通知必须归属某个渠道，且渠道的**重要性、声音、震动**只在渠道
 * **首次创建**时生效：创建之后应用再调用 createNotificationChannel 也无法修改这些属性
 * （系统有意如此，最终决定权在用户手里，用户可以在系统设置里自行调整）。
 * 因此 [ensure] 采用「不存在才创建」的写法，绝不重复创建，也绝不指望改写已有渠道的重要性。
 */
object NotificationChannels {

    /** 上课提醒：高重要性（弹出横幅 + 提示音 + 震动），课前倒计时用它 */
    const val CHANNEL_REMINDER: String = "reminder"

    /** 上课进行中：默认重要性，但发送时静音，作为常驻进度提示不打扰 */
    const val CHANNEL_ONGOING: String = "ongoing"

    private const val REMINDER_NAME = "上课提醒"
    private const val REMINDER_DESC = "课前提醒，含上课倒计时"
    private const val ONGOING_NAME = "上课进行中"
    private const val ONGOING_DESC = "上课期间的常驻进度提示，静默不打扰"

    /** 震动节奏：立即震动 300ms → 停 200ms → 再震 300ms */
    private val REMINDER_VIBRATION = longArrayOf(0L, 300L, 200L, 300L)

    /**
     * 幂等地确保两个渠道存在。可以重复调用（Application 启动、开机广播都会调）。
     */
    fun ensure(context: Context) {
        val manager = context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        ensureReminderChannel(manager)
        ensureOngoingChannel(manager)
    }

    /** 上课提醒渠道：高重要性 + 震动；震动配置在渠道上，不再使用已废弃的 setVibrate */
    private fun ensureReminderChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_REMINDER) != null) return

        val channel = NotificationChannel(
            CHANNEL_REMINDER,
            REMINDER_NAME,
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = REMINDER_DESC
        channel.enableVibration(true)
        channel.vibrationPattern = REMINDER_VIBRATION
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        channel.setSound(
            Settings.System.DEFAULT_NOTIFICATION_URI,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        manager.createNotificationChannel(channel)
    }

    /** 上课进行中渠道：静音、不震动，只做常驻进度展示 */
    private fun ensureOngoingChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ONGOING) != null) return

        val channel = NotificationChannel(
            CHANNEL_ONGOING,
            ONGOING_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = ONGOING_DESC
        channel.enableVibration(false)
        // 双保险：渠道静音 + 发送通知时再 setSilent(true)
        channel.setSound(null, null)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(channel)
    }
}
