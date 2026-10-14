package com.xixi.timetable

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.xixi.timetable.ui.KobiNavHost
import com.xixi.timetable.ui.theme.KobiTheme

/**
 * 唯一的 Activity。
 *
 * 负责两件事：
 * 1. 首次进入时申请通知权限（Android 13+ 的运行时权限）；
 * 2. 挂载 Compose 界面，页面切换交给 KobiNavHost。
 */
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 授权结果无需在此处理：设置页通过 PermissionAuditor 实时读取真实状态
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationIfNeeded()

        setContent {
            KobiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    KobiNavHost(navController = navController)
                }
            }
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
