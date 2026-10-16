package com.xixi.timetable

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.navigation.compose.rememberNavController
import com.xixi.timetable.notify.ReminderScheduler
import com.xixi.timetable.ui.KobiNavHost
import com.xixi.timetable.ui.theme.KobiTheme

/**
 * 唯一的 Activity。
 *
 * 冷启动时序（契约 9.7）：
 * 1. 先 setContent 挂载 Compose 首屏，让首帧尽快可见；
 * 2. 首帧之后再申请通知权限（挂在 decorView.post 上），权限对话框不抢在首屏之前；
 * 3. 同一时机触发一次提醒重排——重排要读数据库，不能占用冷启动窗口。
 *
 * 首屏根 Surface 使用与窗口背景同一个资源色（@color/kobi_window_background），
 * 使「窗口背景 → Compose 首屏」之间不发生颜色跳变。
 */
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 授权结果无需在此处理：设置页通过 PermissionAuditor 实时读取真实状态
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 第 1 步：首屏先上屏。
        setContent {
            KobiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = colorResource(R.color.kobi_window_background)
                ) {
                    val navController = rememberNavController()
                    KobiNavHost(navController = navController)
                }
            }
        }

        // 第 2、3 步：排到首帧之后的主线程消息里执行。
        // ReminderScheduler.rescheduleAll 对外是同步方法，但内部自己切到 IO 线程，
        // 所以这里不会阻塞主线程，也不会与首屏渲染争抢冷启动窗口。
        window.decorView.post {
            requestNotificationIfNeeded()
            ReminderScheduler.rescheduleAll(this)
        }
    }

    /** Android 13（API 33）起通知权限为运行时权限，未授予则全渠道静默 */
    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
