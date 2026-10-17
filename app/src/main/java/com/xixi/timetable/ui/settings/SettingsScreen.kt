@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xixi.timetable.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xixi.timetable.data.schedule.SectionTime
import com.xixi.timetable.data.schedule.SectionTimeTable
import com.xixi.timetable.notify.Notifier
import com.xixi.timetable.notify.PermissionAuditor
import com.xixi.timetable.ui.common.KEY_TERM_START_MILLIS
import com.xixi.timetable.ui.common.SETTINGS_PREFS_NAME
import com.xixi.timetable.ui.common.dayLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 设置页：权限自检、导入入口、通知测试。
 *
 * @param onOpenImport 跳转到导入页。
 * @param onReschedule 立即重排全部提醒。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenImport: () -> Unit = {},
    onReschedule: () -> Unit = {},
    onResetAll: () -> Unit = {}
) {
    val context = LocalContext.current
    var permissionState by remember { mutableStateOf(PermissionAuditor.audit(context)) }
    var canPost by remember { mutableStateOf(Notifier.canPost(context)) }

    // 学期开始日期：第 1 教学周周一 00:00 的毫秒时间戳，0 表示未设置
    val preferences = remember(context) {
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
    }
    var termStartMillis by remember { mutableStateOf(preferences.getLong(KEY_TERM_START_MILLIS, 0L)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    // 节次时间表：逐节的开始/下课时间，通知据此推算每次上课的真实时刻。
    // 直接读写 SectionTimeTable（内部为 SharedPreferences），不需要额外的 ViewModel 状态。
    var sectionTimes by remember { mutableStateOf(SectionTimeTable.load(context)) }
    // 正在编辑的节次；null 表示对话框已关闭
    var editingSection by remember { mutableStateOf<Int?>(null) }
    // 编辑目标是开始时间（true）还是下课时间（false）
    var editingStartOfSection by remember { mutableStateOf(true) }
    // 非法输入的提示；null 表示当前没有提示
    var sectionHint by remember { mutableStateOf<String?>(null) }

    // 从系统设置页返回时自动重新读取真实权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_RESUME) {
                    permissionState = PermissionAuditor.audit(context)
                    canPost = Notifier.canPost(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "设置") },
                actions = {
                    IconButton(
                        onClick = {
                            permissionState = PermissionAuditor.audit(context)
                            canPost = Notifier.canPost(context)
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "重新检查")
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
            item(key = "term-title") {
                SectionTitle(text = "学期设置")
            }

            item(key = "term-start") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "学期开始日期",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (termStartMillis > 0L) {
                                "第 1 教学周周一：${formatDate(termStartMillis)}"
                            } else {
                                "未设置"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (termStartMillis > 0L) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "选任意一天均可，会取该日期所在周的周一 00:00 作为第 1 教学周的起点。" +
                                "不设置将按本周作为第 1 周，提醒时间可能不准。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = if (termStartMillis > 0L) "修改学期开始日期" else "选择学期开始日期")
                        }
                        if (termStartMillis > 0L) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    preferences.edit().remove(KEY_TERM_START_MILLIS).apply()
                                    termStartMillis = 0L
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = "清除该设置")
                            }
                        }
                    }
                }
            }

            item(key = "section-time-title") {
                SectionTitle(text = "节次时间")
            }

            item(key = "section-time") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        val hint = sectionHint
                        if (hint != null) {
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider()
                        }
                        sectionTimes.forEachIndexed { index, row ->
                            SectionTimeRow(
                                sectionTime = row,
                                onEditStart = {
                                    editingSection = row.section
                                    editingStartOfSection = true
                                },
                                onEditEnd = {
                                    editingSection = row.section
                                    editingStartOfSection = false
                                }
                            )
                            if (index != sectionTimes.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "共 ${SectionTimeTable.MAX_SECTION} 节，点击时间可逐节修改开始与下课时刻。" +
                                "通知按这里的时间推算，改完立即保存并重排提醒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                val defaults = SectionTimeTable.defaults()
                                sectionTimes = defaults
                                SectionTimeTable.save(context, defaults)
                                sectionHint = null
                                onReschedule()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Text(text = "恢复默认")
                        }
                    }
                }
            }

            item(key = "permission-title") {
                SectionTitle(text = "提醒权限自检")
            }

            item(key = "permission-list") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        PermissionRow(
                            title = "通知权限",
                            enabled = permissionState.notificationsEnabled,
                            actionLabel = "去开启",
                            onAction = { PermissionAuditor.openNotificationSettings(context) }
                        )
                        HorizontalDivider()
                        PermissionRow(
                            title = "精确闹钟",
                            enabled = permissionState.exactAlarmAllowed,
                            actionLabel = "去开启",
                            onAction = { PermissionAuditor.openExactAlarmSettings(context) }
                        )
                        HorizontalDivider()
                        PermissionRow(
                            title = "电池优化白名单",
                            enabled = permissionState.ignoringBatteryOptimizations,
                            actionLabel = "去设置",
                            onAction = { PermissionAuditor.openBatteryOptimizationSettings(context) }
                        )
                        HorizontalDivider()
                        PermissionRow(
                            title = "后台不受限",
                            enabled = !permissionState.backgroundRestricted,
                            actionLabel = "去设置",
                            onAction = { PermissionAuditor.openAppDetailsSettings(context) }
                        )
                        HorizontalDivider()
                        PermissionRow(
                            title = "全屏提醒",
                            enabled = permissionState.canUseFullScreenIntent,
                            actionLabel = "去设置",
                            onAction = { PermissionAuditor.openAppDetailsSettings(context) }
                        )
                    }
                }
            }

            item(key = "permission-note") {
                Text(
                    text = "小米等机型的「自启动」无法程序化检测，若提醒偶发不响，请同时在系统设置里允许本应用自启动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item(key = "notify-title") {
                SectionTitle(text = "通知与提醒")
            }

            item(key = "notify-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (canPost) "通知可正常发出" else "通知权限未授予，测试通知会被系统静默丢弃",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canPost) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Button(
                        onClick = { onReschedule() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "立即重排全部提醒")
                    }
                    OutlinedButton(
                        onClick = {
                            Notifier.showCountdownReminder(
                                context = context,
                                courseName = "测试课程",
                                location = "教学楼 A101",
                                startAtMillis = System.currentTimeMillis() + 5L * 60L * 1000L
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "测试课前倒计时提醒")
                    }
                    OutlinedButton(
                        onClick = {
                            Notifier.showProgressOngoing(
                                context = context,
                                courseName = "测试课程",
                                location = "教学楼 A101",
                                // currentIndex 为 0 基：第 1 节传 0
                                currentIndex = 0,
                                totalCount = 6
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "测试上课进行中通知")
                    }
                    OutlinedButton(
                        onClick = { Notifier.showTimetableOverview(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "测试课表总览通知")
                    }
                }
            }

            item(key = "import-title") {
                SectionTitle(text = "课表导入")
            }

            item(key = "import-entry") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "支持 CSV、粘贴文本、.ics 日历与 PDF 课表四种来源，解析成功后立即写入课表，" +
                                "无法识别的行会原样列出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onOpenImport,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "进入导入")
                        }
                    }
                }
            }

            item(key = "data-title") {
                SectionTitle(text = "数据管理")
            }

            item(key = "reset") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "一键重置课程数据",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "将删除全部课程与时段，学期设置保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showResetDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "重置课程数据")
                        }
                    }
                }
            }

            item(key = "about") {
                Text(
                    text = "kobi · 课程表",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(text = "确认重置课程数据") },
            text = {
                Text(text = "将删除全部课程与时段，学期设置保留。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetAll()
                    }
                ) {
                    Text(text = "确认重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(text = "取消")
                }
            }
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = if (termStartMillis > 0L) {
                utcDateMillisOf(termStartMillis)
            } else {
                null
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = datePickerState.selectedDateMillis
                        if (picked != null) {
                            val mondayMillis = mondayStartMillisOf(picked)
                            preferences.edit().putLong(KEY_TERM_START_MILLIS, mondayMillis).apply()
                            termStartMillis = mondayMillis
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(text = "确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = "取消")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    val editSection = editingSection
    if (editSection != null) {
        val row = sectionTimes.firstOrNull { it.section == editSection }
        val initialTime = if (editingStartOfSection) row?.start else row?.end
        SectionTimePickerDialog(
            section = editSection,
            isStart = editingStartOfSection,
            initialHour = initialTime?.hour ?: 8,
            initialMinute = initialTime?.minute ?: 0,
            onDismiss = { editingSection = null },
            onConfirm = { picked ->
                val target = sectionTimes.firstOrNull { it.section == editSection }
                when {
                    target == null -> Unit

                    editingStartOfSection && picked >= target.end -> {
                        sectionHint = "第 $editSection 节的开始时间必须早于下课时间 " +
                            "${formatLocalTime(target.end)}，本次修改未保存"
                    }

                    !editingStartOfSection && picked <= target.start -> {
                        sectionHint = "第 $editSection 节的结束时间必须晚于开始时间 " +
                            "${formatLocalTime(target.start)}，本次修改未保存"
                    }

                    else -> {
                        val updated = sectionTimes.map { item ->
                            if (item.section == editSection) {
                                if (editingStartOfSection) {
                                    item.copy(start = picked)
                                } else {
                                    item.copy(end = picked)
                                }
                            } else {
                                item
                            }
                        }
                        sectionTimes = updated
                        SectionTimeTable.save(context, updated)
                        sectionHint = null
                        onReschedule()
                    }
                }
                editingSection = null
            }
        )
    }
}

/**
 * 节次时间编辑对话框。
 *
 * Material3 只提供 TimePicker 本体、没有现成的 TimePickerDialog，因此自己套一层 AlertDialog。
 * 用 key(section, isStart) 强制重建 TimePickerState：否则连续编辑不同节次时，
 * 表盘会停留在上一次的初始值上（remember 只会保留第一次算出的值）。
 */
@Composable
private fun SectionTimePickerDialog(
    section: Int,
    isStart: Boolean,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    key(section, isStart) {
        val timePickerState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(text = "第 $section 节 · " + (if (isStart) "开始时间" else "下课时间"))
            },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(LocalTime.of(timePickerState.hour, timePickerState.minute))
                    }
                ) {
                    Text(text = "确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/**
 * 节次时间表的一行：第 N 节 + 开始 / 下课两个可点的时间按钮。
 */
@Composable
private fun SectionTimeRow(
    sectionTime: SectionTime,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "第 ${sectionTime.section} 节",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = onEditStart) {
            Text(text = formatLocalTime(sectionTime.start))
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onEditEnd) {
            Text(text = formatLocalTime(sectionTime.end))
        }
    }
}

/**
 * 把 DatePicker 返回的 UTC 当天零点毫秒，换算成「该日期所在周的周一 00:00」的本地毫秒。
 */
private fun mondayStartMillisOf(utcDateMillis: Long): Long {
    val date = Instant.ofEpochMilli(utcDateMillis).atZone(ZoneId.of("UTC")).toLocalDate()
    val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return monday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/**
 * 把已存的本地毫秒还原成 DatePicker 需要的「UTC 当天零点」毫秒。
 */
private fun utcDateMillisOf(localMillis: Long): Long {
    val date = Instant.ofEpochMilli(localMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
}

/**
 * 已存的本地毫秒 -> 可读日期，如「2026-02-23（周一）」。
 */
private fun formatDate(localMillis: Long): String {
    val date = Instant.ofEpochMilli(localMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "$date（${dayLabel(date.dayOfWeek.value)}）"
}

/** 节次时间的统一显示格式：24 小时制 HH:mm。 */
private val SectionTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

/**
 * 把节次时间格式化成 HH:mm。
 */
private fun formatLocalTime(time: LocalTime): String = time.format(SectionTimeFormatter)

/**
 * 小节标题。
 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * 单条权限状态行。
 */
@Composable
private fun PermissionRow(
    title: String,
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (enabled) "已就绪" else "未就绪",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onAction) {
            Text(text = actionLabel)
        }
    }
}
