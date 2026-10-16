@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xixi.timetable.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xixi.timetable.data.conflict.Conflict
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.week.WeekPatterns
import com.xixi.timetable.ui.common.dayLabel
import com.xixi.timetable.ui.common.termStartMillisOf
import com.xixi.timetable.ui.common.todayDateText
import com.xixi.timetable.ui.common.weekIndexFromTermStart
import com.xixi.timetable.ui.theme.CoursePalette

/** 一周七天。 */
private const val DAY_COUNT = 7

/** 每天显示的最大节次数量。 */
private const val SECTION_COUNT = 12

/** 左侧节次列宽度。 */
private val timeColumnWidth = 52.dp

/** 单个星期列的宽度。 */
private val dayColumnWidth = 96.dp

/** 单节高度，连堂课程按节数累加。 */
private val sectionHeight = 68.dp

/** 星期表头高度。 */
private val headerHeight = 44.dp

/**
 * 周课表网格（时间 × 星期）。
 *
 * 布局要点：
 * - 左上角节次列与顶部星期表头固定不动；正文区用同一个横向 ScrollState 与节次列共用的
 *   纵向 ScrollState 驱动，因此表头/节次列与内容天然同步，无需手工换算偏移量。
 * - 一个时段渲染为一个课程块，高度 = 节数 × 单节高度，所以连堂课程自动跨多行合并成一格。
 * - 同一天内时间上互相重叠的时段会被分到不同的「泳道」，按泳道数平分列宽并排显示，
 *   互不遮挡（即同一格多门课叠放）。
 *
 * @param sessions 全部时段（内部按当前查看的周次过滤）。
 * @param courses 全部课程，用于取课程名与配色。
 * @param conflicts 全量冲突列表，用于给冲突课程块加红框。
 * @param onSessionClick 点击课程块时回调，通常跳转到编辑页。
 * @param onAddClick 新增课程。
 */
@Composable
fun WeekGridScreen(
    sessions: List<SessionEntity>,
    courses: List<CourseEntity>,
    conflicts: List<Conflict>,
    modifier: Modifier = Modifier,
    onSessionClick: (SessionEntity) -> Unit = {},
    onAddClick: () -> Unit = {}
) {
    val context = LocalContext.current

    // 学期起始日（设置页写入，契约 8.6）；未设置时为 0，周次按第 1 周。
    val termStartMillis = remember { termStartMillisOf(context) }
    // 今天实际所在的教学周，用于在切换条旁标注「当前第 N 周」，与「正在查看的周」区分开。
    val currentWeek = remember(termStartMillis) { weekIndexFromTermStart(termStartMillis) }
    // 全应用唯一的今日日期文案（契约 9.4）。跨天不会自动刷新，与既有 todayOfWeek 口径一致。
    val todayText = remember { todayDateText() }

    // 当前查看的周次。学期起始日已设置时默认定位到当前教学周，否则停在第 1 周；
    // 进入本页后由用户手动切换，不再回写设置。
    var week by rememberSaveable { mutableStateOf(currentWeek) }

    val courseById = remember(courses) { courses.associateBy { it.id } }

    val weekSessions = remember(sessions, week) {
        sessions.filter { WeekPatterns.contains(it.weekPattern, week) }
    }

    val conflictSessionIds = remember(conflicts) {
        val ids = HashSet<Long>()
        conflicts.forEach { conflict ->
            ids.add(conflict.a.id)
            ids.add(conflict.b.id)
        }
        ids
    }

    // 每天一份摆放结果，索引 0 对应周一。
    val blocksByDay = remember(weekSessions) {
        (1..DAY_COUNT).map { day ->
            layoutDay(weekSessions.filter { it.dayOfWeek == day })
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "周课表") },
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            WeekSwitcher(
                week = week,
                currentWeek = currentWeek,
                todayText = todayText,
                termStartSet = termStartMillis > 0L,
                onWeekChange = { week = it }
            )

            if (conflicts.isNotEmpty()) {
                ConflictBanner(conflictCount = conflicts.size)
            }

            if (weekSessions.isEmpty()) {
                Text(
                    text = "第${week}周暂无课程，可切换周次或点右下角新增",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            GridBody(
                blocksByDay = blocksByDay,
                courseById = courseById,
                conflictSessionIds = conflictSessionIds,
                onSessionClick = onSessionClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

/**
 * 周次切换条。
 *
 * 上面一行是今日日期与当前教学周（契约 9.4）；下面一行是**正在查看**的周次，
 * 可用左右按钮切换。两个数字含义不同：前者是今天实际所在的周，后者是当前浏览的周。
 */
@Composable
private fun WeekSwitcher(
    week: Int,
    currentWeek: Int,
    todayText: String,
    termStartSet: Boolean,
    onWeekChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (termStartSet) {
                "今日 $todayText · 当前第 $currentWeek 周"
            } else {
                "今日 $todayText · 未设置学期开始日期，按第 1 周显示"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = { onWeekChange((week - 1).coerceAtLeast(1)) }) {
                Text(text = "上一周")
            }
            Text(
                text = "第 $week 周",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            TextButton(onClick = { onWeekChange((week + 1).coerceAtMost(WeekPatterns.MAX_WEEKS)) }) {
                Text(text = "下一周")
            }
        }
    }
}

/**
 * 冲突提示条。只提示数量，具体冲突在编辑页展开。
 */
@Composable
private fun ConflictBanner(conflictCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "检测到 $conflictCount 处时间冲突，点击课程可修改",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * 网格正文：表头 + 节次列 + 可滚动内容区。
 */
@Composable
private fun GridBody(
    blocksByDay: List<List<GridBlock>>,
    courseById: Map<Long, CourseEntity>,
    conflictSessionIds: Set<Long>,
    onSessionClick: (SessionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    // 表头与内容共用横向滚动状态；节次列与内容共用纵向滚动状态，从而实现同步。
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()

    val gridWidth = dayColumnWidth * DAY_COUNT
    val gridHeight = sectionHeight * SECTION_COUNT

    val rowEvenColor = MaterialTheme.colorScheme.surface
    val rowOddColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier) {
        // 一、固定表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
        ) {
            Box(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "节次",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScrollState)
            ) {
                for (day in 1..DAY_COUNT) {
                    Box(
                        modifier = Modifier
                            .width(dayColumnWidth)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLabel(day),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = lineColor)

        // 二、正文：左侧节次列 + 右侧内容区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .width(timeColumnWidth)
                    .fillMaxHeight()
                    .verticalScroll(verticalScrollState)
            ) {
                for (section in 1..SECTION_COUNT) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(sectionHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = section.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(verticalScrollState)
                    .horizontalScroll(horizontalScrollState)
            ) {
                Box(
                    modifier = Modifier
                        .width(gridWidth)
                        .height(gridHeight)
                ) {
                    // 交替行底色
                    for (section in 0 until SECTION_COUNT) {
                        Box(
                            modifier = Modifier
                                .offset(y = sectionHeight * section)
                                .fillMaxWidth()
                                .height(sectionHeight)
                                .background(if (section % 2 == 0) rowEvenColor else rowOddColor)
                        )
                    }

                    // 横向分节线
                    for (section in 1 until SECTION_COUNT) {
                        Box(
                            modifier = Modifier
                                .offset(y = sectionHeight * section)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(lineColor)
                        )
                    }

                    // 纵向分日线
                    for (day in 1 until DAY_COUNT) {
                        Box(
                            modifier = Modifier
                                .offset(x = dayColumnWidth * day)
                                .width(1.dp)
                                .height(gridHeight)
                                .background(lineColor)
                        )
                    }

                    // 课程块：晚声明 => 绘制在上层
                    blocksByDay.forEachIndexed { dayIndex, blocks ->
                        for (block in blocks) {
                            CourseBlock(
                                block = block,
                                course = courseById[block.session.courseId],
                                hasConflict = conflictSessionIds.contains(block.session.id),
                                dayIndex = dayIndex,
                                onClick = { onSessionClick(block.session) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个课程块。连堂课程由高度自然跨行。
 */
@Composable
private fun CourseBlock(
    block: GridBlock,
    course: CourseEntity?,
    hasConflict: Boolean,
    dayIndex: Int,
    onClick: () -> Unit
) {
    val session = block.session
    val laneWidth = dayColumnWidth / block.laneCount
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .offset(
                x = dayColumnWidth * dayIndex + laneWidth * block.lane,
                y = sectionHeight * (session.startSection - 1)
            )
            .width(laneWidth)
            .height(sectionHeight * (session.endSection - session.startSection + 1))
            .padding(2.dp)
            .clip(shape)
            .background(CoursePalette.colorOf(course?.colorIndex ?: 0))
            .then(
                if (hasConflict) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = course?.name ?: "未命名课程",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (session.location.isNotBlank()) {
                Text(
                    text = session.location,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 一个课程块在网格中的摆放信息。
 *
 * @param lane 所在泳道（0-based），用于横向错开重叠课程。
 * @param laneCount 所属连通簇内的泳道总数，块宽 = 列宽 / laneCount。
 */
internal data class GridBlock(
    val session: SessionEntity,
    val lane: Int,
    val laneCount: Int
)

/**
 * 计算同一天内所有时段的摆放。
 *
 * 先把「时间上互相重叠」的时段划成一个个连通簇（不重叠的簇互不影响宽度），
 * 再在簇内用贪心法分配泳道：能与已有泳道共存的就复用，否则新开一条。
 * 这样普通的单课日仍然占满整列宽度，只有真正重叠时才并排显示。
 */
internal fun layoutDay(daySessions: List<SessionEntity>): List<GridBlock> {
    if (daySessions.isEmpty()) return emptyList()

    val sorted = daySessions.sortedWith(
        compareBy<SessionEntity> { it.startSection }.thenBy { it.endSection }
    )
    val result = mutableListOf<GridBlock>()

    var index = 0
    while (index < sorted.size) {
        // 划出一个连通簇：后续时段的起始节次不超过当前簇的最大结束节次即为重叠
        val clusterStart = index
        var clusterMaxEnd = sorted[index].endSection
        index++
        while (index < sorted.size && sorted[index].startSection <= clusterMaxEnd) {
            if (sorted[index].endSection > clusterMaxEnd) {
                clusterMaxEnd = sorted[index].endSection
            }
            index++
        }
        val cluster = sorted.subList(clusterStart, index)

        // 簇内贪心分泳道
        val laneEnds = mutableListOf<Int>()
        val laneOf = mutableListOf<Int>()
        for (session in cluster) {
            var targetLane = -1
            for (lane in laneEnds.indices) {
                if (laneEnds[lane] < session.startSection) {
                    targetLane = lane
                    break
                }
            }
            if (targetLane == -1) {
                laneEnds.add(session.endSection)
                targetLane = laneEnds.size - 1
            } else {
                laneEnds[targetLane] = session.endSection
            }
            laneOf.add(targetLane)
        }

        val laneCount = laneEnds.size
        for (i in cluster.indices) {
            result.add(
                GridBlock(
                    session = cluster[i],
                    lane = laneOf[i],
                    laneCount = laneCount
                )
            )
        }
    }

    return result
}
