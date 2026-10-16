@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.xixi.timetable.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.week.WeekPatterns
import com.xixi.timetable.ui.common.dayLabel
import com.xixi.timetable.ui.common.sectionRangeText
import com.xixi.timetable.ui.common.termStartMillisOf
import com.xixi.timetable.ui.common.todayDateText
import com.xixi.timetable.ui.common.todayOfWeek
import com.xixi.timetable.ui.common.tomorrowDateText
import com.xixi.timetable.ui.common.tomorrowOfWeek
import com.xixi.timetable.ui.common.weekIndexFromTermStart
import com.xixi.timetable.ui.theme.CoursePalette

/**
 * 多选模式的选中集合持久化：旋转屏幕后不丢失。
 *
 * 存的是**课程 id** 而不是时段 id：列表按「时段」展示，同一门课的多个时段会重复出现，
 * 按课程 id 收集天然完成去重，删除时不会重复提交同一门课程。
 */
private val SelectedCourseIdsSaver = listSaver<MutableState<Set<Long>>, Long>(
    save = { state -> state.value.toList() },
    restore = { saved -> mutableStateOf(saved.toSet()) }
)

/**
 * 列表视图。
 *
 * 先按「当前教学周」筛选（与周课表网格页同一口径，避免两页语义矛盾），
 * 再按「今天 / 明天 / 本周」三组展示：
 * - 今天、明天各是确定的某一天，分组标题带真实日期；
 * - 本周收集剩下所有星期几的课程，保证本周内不会漏课。
 *
 * 教学周取自设置页写入的学期起始日（契约 8.6）；未设置时回退为第 1 周，
 * 与网格页的默认口径完全一致，并在提示行明示「未设置学期开始日期」。
 *
 * 删除交互：**长按**任意一条进入多选模式（长按的那门课自动选中），顶部出现删除按钮，
 * 点击其它条目继续勾选，一次删除；删除前有二次确认。未采用滑动删除——`SwipeToDismissBox`
 * 属材料库实验 API，其手势会与列表项自身的点击/长按互相抢占，且本机无 JDK 无法验证签名。
 *
 * @param onDeleteCourses 批量删除回调；参数已按课程 id 去重。
 */
@Composable
fun CourseListScreen(
    sessions: List<SessionEntity>,
    courses: List<CourseEntity>,
    modifier: Modifier = Modifier,
    onSessionClick: (SessionEntity) -> Unit = {},
    onAddClick: () -> Unit = {},
    onDeleteCourses: (List<Long>) -> Unit = {}
) {
    val context = LocalContext.current

    // 学期起始日（设置页写入，契约 8.6）。未设置时为 0，周次按第 1 周，
    // 与网格页完全相同的回退口径。
    val termStartMillis = remember { termStartMillisOf(context) }
    val termStartSet = termStartMillis > 0L
    val currentWeek = remember(termStartMillis) { weekIndexFromTermStart(termStartMillis) }

    // 全应用唯一的日期文案来源（契约 9.4）
    val todayText = remember { todayDateText() }
    val tomorrowText = remember { tomorrowDateText() }

    val today = remember { todayOfWeek() }
    val tomorrow = remember { tomorrowOfWeek() }

    val courseById = remember(courses) { courses.associateBy { it.id } }

    // 多选模式：选中集合按课程 id 保存，旋转后仍保留
    var selectedCourseIds by rememberSaveable(saver = SelectedCourseIdsSaver) {
        mutableStateOf(emptySet<Long>())
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val selectionMode = selectedCourseIds.isNotEmpty()

    // 先按教学周筛选：单周课不会出现在双周，学期外与学期已过的课也不会常驻
    val visibleSessions = remember(sessions, currentWeek) {
        sessions
            .filter { WeekPatterns.contains(it.weekPattern, currentWeek) }
            .sortedWith(compareBy<SessionEntity> { it.startSection }.thenBy { it.endSection })
    }

    val sections = remember(visibleSessions, today, tomorrow, todayText, tomorrowText) {
        listOf(
            "今天 · $todayText" to visibleSessions.filter { it.dayOfWeek == today },
            "明天 · $tomorrowText" to visibleSessions.filter { it.dayOfWeek == tomorrow },
            "本周" to visibleSessions
                .filter { it.dayOfWeek != today && it.dayOfWeek != tomorrow }
                .sortedWith(
                    compareBy<SessionEntity> { it.dayOfWeek }.thenBy { it.startSection }
                )
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectionMode) {
                            "已选 ${selectedCourseIds.size} 门课程"
                        } else {
                            "课程列表"
                        }
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedCourseIds = emptySet() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "退出多选"
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除所选课程"
                            )
                        }
                    } else {
                        IconButton(onClick = onAddClick) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "新增课程")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "新增课程")
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            item(key = "week-hint") {
                Text(
                    text = if (termStartSet) {
                        "今日 $todayText · 当前第 $currentWeek 教学周"
                    } else {
                        "今日 $todayText · 未设置学期开始日期，按第 1 周显示"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                )
            }

            if (selectionMode) {
                item(key = "selection-hint") {
                    Text(
                        text = "点击条目继续勾选，右上角可删除；已选 " +
                            "${selectedCourseIds.size} 门课程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    )
                }
            }

            sections.forEach { (title, groupSessions) ->
                item(key = "header-$title") {
                    GroupHeader(title = title, count = groupSessions.size)
                }
                if (groupSessions.isEmpty()) {
                    item(key = "empty-$title") {
                        Text(
                            text = "没有课程",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        )
                    }
                } else {
                    items(items = groupSessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            course = courseById[session.courseId],
                            selectionMode = selectionMode,
                            selected = selectedCourseIds.contains(session.courseId),
                            onClick = {
                                if (selectionMode) {
                                    selectedCourseIds =
                                        toggleId(selectedCourseIds, session.courseId)
                                } else {
                                    onSessionClick(session)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectedCourseIds = selectedCourseIds + session.courseId
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        val count = selectedCourseIds.size
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "删除课程") },
            text = {
                Text(
                    text = "将删除选中的 $count 门课程及其全部时段，并重新安排提醒；" +
                        "同一门课程的多个时段只会删除一次。此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        // 集合本身即按 courseId 去重
                        onDeleteCourses(selectedCourseIds.toList())
                        selectedCourseIds = emptySet()
                    }
                ) {
                    Text(text = "删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/**
 * 在集合中切换某个 id 的选中状态。
 */
private fun toggleId(ids: Set<Long>, id: Long): Set<Long> =
    if (ids.contains(id)) ids - id else ids + id

/**
 * 分组标题。
 */
@Composable
private fun GroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count 节",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单条课程记录。左侧色条即课程配色。
 *
 * @param selectionMode 是否处于多选模式（届时右侧显示勾选框）。
 * @param selected 该条所属课程是否已被选中。
 * @param onLongClick 长按回调，用于进入多选模式。
 */
@Composable
private fun SessionRow(
    session: SessionEntity,
    course: CourseEntity?,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                    .background(CoursePalette.colorOf(course?.colorIndex ?: 0))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = course?.name ?: "未命名课程",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildSubtitle(session = session, course = course),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = null)
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

/**
 * 副标题：节次 + 周次 + 教师 + 地点（地点只展示文字）。
 */
private fun buildSubtitle(session: SessionEntity, course: CourseEntity?): String {
    val parts = mutableListOf<String>()
    parts.add(dayLabel(session.dayOfWeek))
    parts.add(sectionRangeText(session.startSection, session.endSection))
    parts.add(WeekPatterns.describe(session.weekPattern))
    if (course != null && course.teacher.isNotBlank()) {
        parts.add(course.teacher)
    }
    if (session.location.isNotBlank()) {
        parts.add(session.location)
    }
    return parts.joinToString(separator = " · ")
}
