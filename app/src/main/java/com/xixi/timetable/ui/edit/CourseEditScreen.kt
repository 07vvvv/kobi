@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xixi.timetable.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xixi.timetable.data.conflict.Conflict
import com.xixi.timetable.data.conflict.ConflictDetector
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.week.OddEven
import com.xixi.timetable.data.week.WeekPatterns
import com.xixi.timetable.ui.MainViewModel
import com.xixi.timetable.ui.common.dayLabel
import com.xixi.timetable.ui.theme.CoursePalette

/** 单周/双周选项的展示顺序。 */
private val oddEvenOptions = listOf(
    OddEven.ALL to "每周",
    OddEven.ODD to "单周",
    OddEven.EVEN to "双周"
)

/**
 * 课程新增 / 编辑页。
 *
 * @param courseId null 或 0 表示新建；正数表示编辑已有课程。
 * @param viewModel 界面层唯一数据入口。
 * @param onSaved 保存成功后的回调（一般返回上一页）。
 * @param onBack 放弃编辑返回。
 */
@Composable
fun CourseEditScreen(
    courseId: Long?,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val isEditing = courseId != null && courseId > 0L

    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var colorIndex by remember { mutableStateOf(0) }
    var keySeed by remember { mutableStateOf(0L) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val draftSessions = remember { mutableStateListOf<SessionDraft>() }

    // 回填：编辑时读库，新建时给一个默认时段
    LaunchedEffect(courseId) {
        if (isEditing && courseId != null) {
            viewModel.loadCourse(courseId) { entity, sessionList ->
                if (entity != null) {
                    name = entity.name
                    teacher = entity.teacher
                    note = entity.note
                    colorIndex = entity.colorIndex
                }
                draftSessions.clear()
                sessionList.forEach { session ->
                    val weeks = WeekPatterns.weeksOf(session.weekPattern)
                    val from = weeks.firstOrNull() ?: 1
                    val to = weeks.lastOrNull() ?: WeekPatterns.MAX_WEEKS
                    draftSessions.add(
                        SessionDraft(
                            key = keySeed++,
                            sessionId = session.id,
                            dayOfWeek = session.dayOfWeek,
                            startSection = session.startSection,
                            endSection = session.endSection,
                            location = session.location,
                            startWeek = from,
                            endWeek = to,
                            oddEven = oddEvenOf(session.weekPattern, from, to)
                        )
                    )
                }
            }
        } else if (draftSessions.isEmpty()) {
            draftSessions.add(SessionDraft(key = keySeed++))
        }
    }

    // 本地冲突检测：直接对编辑中的草稿时段两两比较，不依赖已存库的数据
    val draftConflicts: List<Conflict> = remember(draftSessions.toList()) {
        ConflictDetector.findConflicts(
            draftSessions.map { draft -> draft.toEntity(courseId = courseId ?: 0L) }
        )
    }

    val nameIsBlank = name.isBlank()
    val canSave = !nameIsBlank && draftSessions.isNotEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = if (isEditing) "编辑课程" else "新增课程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (canSave) {
                                viewModel.saveCourse(
                                    course = CourseEntity(
                                        id = courseId ?: 0L,
                                        name = name.trim(),
                                        teacher = teacher.trim(),
                                        colorIndex = colorIndex,
                                        note = note.trim()
                                    ),
                                    sessions = draftSessions.map { draft ->
                                        draft.toEntity(courseId = courseId ?: 0L)
                                    },
                                    onDone = onSaved
                                )
                            }
                        },
                        enabled = canSave
                    ) {
                        Text(text = "保存")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = "课程名") },
                    singleLine = true,
                    isError = nameIsBlank,
                    supportingText = {
                        if (nameIsBlank) {
                            Text(text = "课程名不能为空")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item(key = "teacher") {
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text(text = "教师（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item(key = "note") {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(text = "备注（可留空）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item(key = "color") {
                Column {
                    Text(
                        text = "课程配色",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoursePalette.colors.forEachIndexed { index, color ->
                            val selected = index == colorIndex
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (selected) {
                                            Modifier.border(
                                                width = 3.dp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                shape = CircleShape
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { colorIndex = index }
                            )
                        }
                    }
                }
            }

            if (draftConflicts.isNotEmpty()) {
                item(key = "conflicts") {
                    ConflictHintCard(conflicts = draftConflicts)
                }
            }

            item(key = "sessions-title") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "上课时段",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${draftSessions.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            val last = draftSessions.lastOrNull()
                            draftSessions.add(
                                SessionDraft(
                                    key = keySeed++,
                                    dayOfWeek = last?.dayOfWeek ?: 1,
                                    startSection = last?.endSection?.plus(1)?.coerceAtMost(12) ?: 1,
                                    endSection = last?.endSection?.plus(1)?.coerceAtMost(12) ?: 2,
                                    startWeek = last?.startWeek ?: 1,
                                    endWeek = last?.endWeek ?: WeekPatterns.MAX_WEEKS,
                                    oddEven = last?.oddEven ?: OddEven.ALL
                                )
                            )
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "添加时段")
                    }
                }
            }

            draftSessions.forEachIndexed { index, draft ->
                item(key = "session-${draft.key}") {
                    SessionEditor(
                        index = index,
                        draft = draft,
                        onChange = { updated -> draftSessions[index] = updated },
                        onRemove = {
                            if (draftSessions.size > 1) {
                                draftSessions.removeAt(index)
                            }
                        },
                        removable = draftSessions.size > 1
                    )
                }
            }

            item(key = "save") {
                Column {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (canSave) {
                                viewModel.saveCourse(
                                    course = CourseEntity(
                                        id = courseId ?: 0L,
                                        name = name.trim(),
                                        teacher = teacher.trim(),
                                        colorIndex = colorIndex,
                                        note = note.trim()
                                    ),
                                    sessions = draftSessions.map { draft ->
                                        draft.toEntity(courseId = courseId ?: 0L)
                                    },
                                    onDone = onSaved
                                )
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "保存")
                    }

                    if (isEditing && courseId != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "删除这门课程")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog && courseId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "删除课程") },
            text = { Text(text = "删除后该课程的全部时段与已排提醒都会一并清除，且无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteCourse(courseId)
                        onBack()
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
 * 冲突提示卡：列出每一处冲突的原因。
 */
@Composable
private fun ConflictHintCard(conflicts: List<Conflict>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "发现 ${conflicts.size} 处时间冲突",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            conflicts.take(5).forEach { conflict ->
                Text(
                    text = "· ${conflict.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (conflicts.size > 5) {
                Text(
                    text = "… 其余 ${conflicts.size - 5} 处略",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = "仍然可以保存，冲突只作提示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * 单个时段的编辑卡片。
 */
@Composable
private fun SessionEditor(
    index: Int,
    draft: SessionDraft,
    onChange: (SessionDraft) -> Unit,
    onRemove: () -> Unit,
    removable: Boolean
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "时段 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, enabled = removable) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "删除该时段")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DayDropdown(
                    label = "星期",
                    value = draft.dayOfWeek,
                    onValueChange = { onChange(draft.copy(dayOfWeek = it)) },
                    modifier = Modifier.weight(1f)
                )
                NumberDropdown(
                    label = "起始节",
                    value = draft.startSection,
                    range = 1..12,
                    onValueChange = { picked ->
                        val newEnd = if (picked > draft.endSection) picked else draft.endSection
                        onChange(draft.copy(startSection = picked, endSection = newEnd))
                    },
                    modifier = Modifier.weight(1f)
                )
                NumberDropdown(
                    label = "结束节",
                    value = draft.endSection,
                    range = 1..12,
                    onValueChange = { picked ->
                        val newStart = if (picked < draft.startSection) picked else draft.startSection
                        onChange(draft.copy(startSection = newStart, endSection = picked))
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = draft.location,
                onValueChange = { onChange(draft.copy(location = it)) },
                label = { Text(text = "地点（只作文字展示）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NumberDropdown(
                    label = "从第",
                    value = draft.startWeek,
                    range = 1..WeekPatterns.MAX_WEEKS,
                    onValueChange = { picked ->
                        val newEnd = if (picked > draft.endWeek) picked else draft.endWeek
                        onChange(draft.copy(startWeek = picked, endWeek = newEnd))
                    },
                    modifier = Modifier.weight(1f)
                )
                NumberDropdown(
                    label = "到第",
                    value = draft.endWeek,
                    range = 1..WeekPatterns.MAX_WEEKS,
                    onValueChange = { picked ->
                        val newStart = if (picked < draft.startWeek) picked else draft.startWeek
                        onChange(draft.copy(startWeek = newStart, endWeek = picked))
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                oddEvenOptions.forEach { (option, label) ->
                    FilterChip(
                        selected = draft.oddEven == option,
                        onClick = { onChange(draft.copy(oddEven = option)) },
                        label = { Text(text = label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "周次预览：${WeekPatterns.describe(draft.weekPatternMask())}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 星期下拉选择。
 */
@Composable
private fun DayDropdown(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "$label ${dayLabel(value)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (day in 1..7) {
                DropdownMenuItem(
                    text = { Text(text = dayLabel(day)) },
                    onClick = {
                        onValueChange(day)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 数字下拉选择（节次、周次复用）。
 */
@Composable
private fun NumberDropdown(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "$label $value")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            range.forEach { number ->
                DropdownMenuItem(
                    text = { Text(text = number.toString()) },
                    onClick = {
                        onValueChange(number)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 编辑中的时段草稿。
 *
 * @param key 仅用于列表项稳定标识，与数据库主键无关。
 * @param sessionId 已有数据库主键；新建时段为 0。
 */
private data class SessionDraft(
    val key: Long,
    val sessionId: Long = 0L,
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 2,
    val location: String = "",
    val startWeek: Int = 1,
    val endWeek: Int = WeekPatterns.MAX_WEEKS,
    val oddEven: OddEven = OddEven.ALL
) {
    /** 由「起止周 + 单双周」折算周次位掩码。 */
    fun weekPatternMask(): Long {
        val from = minOf(startWeek, endWeek)
        val to = maxOf(startWeek, endWeek)
        return WeekPatterns.fromRange(from, to, oddEven)
    }

    /** 转成数据层实体。courseId 为 0 时由数据层在写库后回填。 */
    fun toEntity(courseId: Long): SessionEntity = SessionEntity(
        id = sessionId,
        courseId = courseId,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        location = location.trim(),
        weekPattern = weekPatternMask()
    )
}

/**
 * 由掩码与起止周反推单双周设置，用于回填。
 */
private fun oddEvenOf(mask: Long, start: Int, end: Int): OddEven {
    if (mask == WeekPatterns.fromRange(start, end, OddEven.ALL)) return OddEven.ALL
    if (mask == WeekPatterns.fromRange(start, end, OddEven.ODD)) return OddEven.ODD
    if (mask == WeekPatterns.fromRange(start, end, OddEven.EVEN)) return OddEven.EVEN
    return OddEven.ALL
}
