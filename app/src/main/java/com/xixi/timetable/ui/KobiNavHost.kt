@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xixi.timetable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.xixi.timetable.ui.edit.CourseEditScreen
import com.xixi.timetable.ui.grid.WeekGridScreen
import com.xixi.timetable.ui.importer.ImportScreen
import com.xixi.timetable.ui.list.CourseListScreen
import com.xixi.timetable.ui.settings.SettingsScreen

/**
 * 全部页面路由。
 */
object Routes {
    const val GRID: String = "grid"
    const val LIST: String = "list"

    /** 新建课程。 */
    const val EDIT: String = "edit"

    /** 编辑已有课程，带 courseId 参数。 */
    const val EDIT_WITH_ID: String = "edit/{courseId}"

    /** 构造编辑指定课程的路由。 */
    fun editOf(courseId: Long): String = "edit/$courseId"

    const val SETTINGS: String = "settings"
    const val IMPORT: String = "import"
}

/** 底部栏的一项。 */
private data class TopTab(val route: String, val label: String, val icon: ImageVector)

/**
 * 应用的全部页面装配。
 *
 * MainActivity 只调用这一个入口。数据来自唯一的 MainViewModel，各页面共享同一份状态。
 */
@Composable
fun KobiNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val vm: MainViewModel = viewModel()

    val courses by vm.courses.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val conflicts by vm.conflicts.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    // 首帧还没有 back stack 信息，按课表页处理，避免底部栏闪烁
    val currentRoute = backStackEntry?.destination?.route ?: Routes.GRID

    val tabs = remember {
        listOf(
            TopTab(route = Routes.GRID, label = "课表", icon = Icons.Default.DateRange),
            TopTab(route = Routes.LIST, label = "列表", icon = Icons.Default.Menu),
            TopTab(route = Routes.SETTINGS, label = "设置", icon = Icons.Default.Settings)
        )
    }

    val topLevelRoutes = remember { setOf(Routes.GRID, Routes.LIST, Routes.SETTINGS) }

    Column(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.GRID,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            composable(route = Routes.GRID) {
                WeekGridScreen(
                    sessions = sessions,
                    courses = courses,
                    conflicts = conflicts,
                    onSessionClick = { session ->
                        navController.navigate(Routes.editOf(session.courseId))
                    },
                    onAddClick = { navController.navigate(Routes.EDIT) }
                )
            }

            composable(route = Routes.LIST) {
                CourseListScreen(
                    sessions = sessions,
                    courses = courses,
                    onSessionClick = { session ->
                        navController.navigate(Routes.editOf(session.courseId))
                    },
                    onAddClick = { navController.navigate(Routes.EDIT) },
                    onDeleteCourses = { courseIds -> vm.deleteCourses(courseIds) }
                )
            }

            composable(route = Routes.EDIT) {
                CourseEditScreen(
                    courseId = null,
                    viewModel = vm,
                    onSaved = { navController.popBackStack() },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.EDIT_WITH_ID,
                arguments = listOf(navArgument("courseId") { type = NavType.LongType })
            ) { entry ->
                val courseId = entry.arguments?.getLong("courseId") ?: 0L
                CourseEditScreen(
                    courseId = courseId,
                    viewModel = vm,
                    onSaved = { navController.popBackStack() },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(route = Routes.SETTINGS) {
                SettingsScreen(
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onReschedule = { vm.rescheduleReminders() },
                    onResetAll = { vm.resetAll() }
                )
            }

            composable(route = Routes.IMPORT) {
                ImportScreen(
                    viewModel = vm,
                    onDone = { navController.popBackStack() }
                )
            }
        }

        if (topLevelRoutes.contains(currentRoute)) {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(imageVector = tab.icon, contentDescription = tab.label)
                        },
                        label = { Text(text = tab.label) }
                    )
                }
            }
        }
    }
}
