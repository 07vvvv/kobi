@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xixi.timetable.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xixi.timetable.data.importer.ImportResult
import com.xixi.timetable.data.ocr.ImageTextRecognizer
import com.xixi.timetable.ui.ImportKind
import com.xixi.timetable.ui.MainViewModel
import kotlinx.coroutines.launch

/** 四种来源的展示名，顺序必须与 ImportKind（CSV, TEXT, ICS, IMAGE）一致。 */
private val kindLabels = listOf("CSV 表格", "粘贴文本", "ICS 日历", "图片识别")

/** 每种来源的输入提示，与数据层导入器的实际行为保持一致。 */
private val kindHints = listOf(
    "首行为表头，顺序固定：课程名,教师,地点,星期,起始节次,结束节次,周次",
    "每行一门课，逗号、制表符、空格分隔均可。行内先认周次（支持「1-16周(单)」「1-16周,单周」" +
        "「单周」等写法）、再认节次、再认星期；剩下的片段按特征归类：像教室的（含楼/馆/室，或 " +
        "A101 这类）判为地点，2~4 个汉字的判为教师，最长的一片判为课程名。" +
        "没写周次时按 1-30 周全周处理。示例：高等数学 张老师 教一101 周一 1-2节 1-16周 单周",
    ".ics 里的钟点时间会按作息表换算成节次：第 1-12 节的起始时间为 " +
        "08:00 / 08:55 / 10:00 / 10:55 / 14:00 / 14:55 / 16:00 / 16:55 / 19:00 / 19:55 / 20:50 / 21:45，" +
        "每节 55 分钟，匹配容差 30 分钟",
    "点「选择图片」调用系统图片选择器（无需存储权限）。识别结果会覆盖填到上方文本框，" +
        "请核对无误后再点「解析并导入」；图片识别不会跳过核对直接写入课表。"
)

/**
 * 导入页：选择来源 → 粘贴内容或选图识别 → 解析导入 → 查看结果。
 *
 * 图片识别（[ImportKind.IMAGE]）只负责把图片转成文本并回填文本框，
 * 最终仍由用户点「解析并导入」按 TEXT 流程入库。
 *
 * @param onDone 导入结束后返回上一页。
 */
@Composable
fun ImportScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {}
) {
    val kinds = remember { ImportKind.values().toList() }

    var kindIndex by rememberSaveable { mutableStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val safeIndex = kindIndex.coerceIn(0, kinds.lastIndex.coerceAtLeast(0))
    val kind = kinds[safeIndex]

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 系统图片选择器：PickVisualMedia 无需存储权限。用户取消时 uri 为 null。
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { picked: Uri? ->
        if (picked == null) {
            status = "已取消选择图片"
        } else {
            busy = true
            status = "正在识别图片，请稍候…"
            scope.launch {
                // 接口自身承诺不抛异常，这里再兜一层，保证界面永不崩。
                val recognized = try {
                    ImageTextRecognizer.recognize(context, picked)
                } catch (t: Throwable) {
                    ""
                }
                busy = false
                if (recognized.isBlank()) {
                    status = "没有识别到文字，请换一张更清晰、文字更完整的图片重试"
                } else {
                    // 覆盖式回填到粘贴框，用户核对后仍需手动点「解析并导入」。
                    text = recognized
                    result = null
                    val lineCount = recognized.split('\n').count { it.isNotBlank() }
                    status = "已识别 $lineCount 行，请核对后再点「解析并导入」"
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "导入课表") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
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
            item(key = "kind") {
                Column {
                    Text(
                        text = "来源格式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 四个标签横排可能超出窄屏宽度，允许横向滚动，保证「图片识别」始终可达。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        kinds.forEachIndexed { index, _ ->
                            FilterChip(
                                selected = index == safeIndex,
                                onClick = {
                                    kindIndex = index
                                    result = null
                                    status = null
                                },
                                label = { Text(text = kindLabels.getOrElse(index) { "未知" }) }
                            )
                        }
                    }
                }
            }

            if (kind == ImportKind.IMAGE) {
                item(key = "pick-image") {
                    Column {
                        Button(
                            onClick = {
                                // 官方 Kotlin 示例写法：顶层工厂函数 PickVisualMediaRequest(mediaType)。
                                pickImage.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "选择图片")
                        }
                        val currentStatus = status
                        if (currentStatus != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = currentStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item(key = "input") {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        result = null
                    },
                    label = { Text(text = "粘贴课表内容") },
                    minLines = 8,
                    supportingText = { Text(text = kindHints.getOrElse(safeIndex) { "" }) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item(key = "action") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            busy = true
                            // 图片识别模式下文本已由 OCR 回填，按 TEXT 走既有文本解析流程。
                            // （MainViewModel 的 IMAGE 分支只用于兜底报错，界面不应走到它。）
                            val parseKind = if (kind == ImportKind.IMAGE) ImportKind.TEXT else kind
                            viewModel.importFromText(text = text, kind = parseKind) { importResult ->
                                result = importResult
                                busy = false
                            }
                        },
                        enabled = text.isNotBlank() && !busy,
                    ) {
                        Text(text = "解析并导入")
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            item(key = "action-note") {
                Text(
                    text = "注意：每条记录都会新建一门课程，同一门课的多个时段会变成多门同名课程；" +
                        "重复导入会产生重复数据，导入前建议先清理旧课表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val current = result
            if (current != null) {
                item(key = "result") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "解析成功 ${current.items.size} 条，已写入课表",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "无法识别 ${current.errors.size} 行",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (current.errors.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }

                if (current.errors.isNotEmpty()) {
                    item(key = "errors-title") {
                        Text(
                            text = "无法识别的行",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    current.errors.forEachIndexed { index, error ->
                        item(key = "error-$index") {
                            Text(
                                text = "· $error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item(key = "done") {
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "完成并返回")
                    }
                }
            }
        }
    }
}
