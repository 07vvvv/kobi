@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xixi.timetable.ui.list

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.xixi.timetable.ui.common.KEY_TERM_START_MILLIS
import com.xixi.timetable.ui.common.SETTINGS_PREFS_NAME
import com.xixi.timetable.ui.common.dayLabel
import com.xixi.timetable.ui.common.sectionRangeText
import com.xixi.timetable.ui.common.todayOfWeek
import com.xixi.timetable.ui.common.tomorrowOfWeek
import com.xixi.timetable.ui.common.weekIndexFromTermStart
import com.xixi.timetable.ui.theme.CoursePalette

/**
 * 列表视图。
 *
 * 先按「当前教学周」筛选（与周课表网格页同一口径，避免两页语义矛盾），
 * 再按「今天 / 明天 / 本周」三组展示：
 * - 今天、明天各是确定的某一天；
 * - 本周收集剩下所有星期几的课程，保证本周内不会漏课。
 *
 * 教学周取自设置页写入的学期起始日（契约 8.6）；未设置时回退为第 1 周，
 * 与网格页 `weekIndexFromTermStart` 的默认口径完全一致。
 */
@Composable
fun CourseListScreen(
    sessions: List<SessionEntity>,
    courses: List<CourseEntity>,
    modifier: Modifier = Modifier,
    onSessionClick: (SessionEntity) -> Unit = {},
    onAddClick: () -> Unit = {}
) {
    val context = LocalContext.current

    // 当前教学周：与周课表网格页读同一份设置、用同一个换算函数。
    // 未设置学期起始日时 weekIndexFromTermStart 回退为第 1 周，两页保持一致。
    val currentWeek = remember {
        val preferences = context.getSharedPreferences(
            SETTINGS_PREFS_NAME,
            Context.MODE_PRIVATE
        )
        weekIndexFromTermStart(preferences.getLong(KEY_TERM_START_MILLIS, 0L))
    }

    val today = remember { todayOfWeek() }
    val tomorrow = remember { tomorrowOfWeek() }

    val courseById = remember(courses) { courses.associateBy { it.id } }

    // 先按教学周筛选：单周课不会出现在双周，学期外与学期已过的课也不会常驻
    val visibleSessions = remember(sessions, currentWeek) {
        sessions
            .filter { WeekPatterns.contains(it.weekPattern, currentWeek) }
            .sortedWith(compareBy<SessionEntity> { it.startSection }.thenBy { it.endSection })
    }

    val sections = remember(visibleSessions, today, tomorrow) {
        listOf(
            "今天 · ${dayLabel(today)}" to visibleSessions.filter { it.dayOfWeek == today },
            "明天 · ${dayLabel(tomorrow)}" to visibleSessions.filter { it.dayOfWeek == tomorrow },
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
                title = { Text(text = "课程列表") },
                actions = {
                    IconButton(onClick = onAddClick) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "新增课程")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "新增课程")
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
                    text = "按第 $currentWeek 教学周筛选；学期开始日期可在设置页调整",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                )
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
                            onClick = { onSessionClick(session) }
                        )
                    }
                }
            }
        }
    }
}

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
 */
@Composable
private fun SessionRow(
    session: SessionEntity,
    course: CourseEntity?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxSize()
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
