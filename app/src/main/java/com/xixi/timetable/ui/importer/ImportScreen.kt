@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xixi.timetable.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.xixi.timetable.data.pdf.PdfTimetableImporter
import com.xixi.timetable.ui.ImportKind
import com.xixi.timetable.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 四种来源的展示名，顺序必须与 ImportKind（CSV, TEXT, ICS, PDF）一致。 */
private val kindLabels = listOf("CSV 表格", "粘贴文本", "ICS 日历", "PDF 课表")

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
    "点「选择 PDF 文件」从系统文件选择器里挑一份课表 PDF。只对教务系统导出的矢量文本 PDF " +
        "有效：按文字的页面坐标重建课表（表头认星期、最左列认节次），重建结果会填到上方文本框，" +
        "请核对无误后再点「解析并导入」——PDF 不会跳过核对直接写入课表；" +
        "扫描件、图片版 PDF 里没有文字，识别不出来。"
)

/**
 * 导入页：选择来源 → 粘贴内容或选择 PDF 重建 → 解析导入 → 查看结果。
 *
 * PDF 课表（[ImportKind.PDF]）分两步：先读取 PDF 字节并交给 [PdfTimetableImporter]
 * 按坐标重建为**规范化文本**回填文本框；重建不出结构时给出可读原因。两种情况都只回填
 * 文本，最终仍由用户点「解析并导入」按 TEXT 流程入库，绝不跳过核对。
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

    // 系统文件选择器：OpenDocument 只筛 PDF，无需存储权限。用户取消时 uri 为 null。
    val pickPdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { picked: Uri? ->
        if (picked == null) {
            status = "已取消选择文件"
        } else {
            val pickedUri = picked
            busy = true
            status = "正在读取 PDF，请稍候…"
            scope.launch {
                // 读字节与解析都是阻塞式工作（解析是纯 CPU，且可能要解压流），
                // 统一切到 IO 线程，完成后自动回到主线程更新 state。
                val parsed = withContext(Dispatchers.IO) {
                    // 解析器承诺绝不抛异常；这里再兜一层，保证界面永不崩、busy 不卡死。
                    try {
                        val bytes = context.contentResolver.openInputStream(pickedUri)?.use { input ->
                            input.readBytes()
                        }
                        if (bytes == null || bytes.isEmpty()) {
                            null
                        } else {
                            PdfTimetableImporter.parse(bytes)
                        }
                    } catch (t: Throwable) {
                        null
                    }
                }
                busy = false

                if (parsed == null) {
                    status = "读取 PDF 失败：文件打不开或是空文件，请换一份再试"
                } else {
                    val outcome = parsed
                    when {
                        outcome.lines.isNotEmpty() -> {
                            // 回填重建出的规范化文本，用户核对后仍需手动点「解析并导入」。
                            text = outcome.lines.joinToString("\n")
                            result = null
                            status = if (outcome.droppedLineCount > 0) {
                                "已从 PDF 重建 ${outcome.lines.size} 个时段（另有 " +
                                    "${outcome.droppedLineCount} 行无法识别，未填入），" +
                                    "请核对后再点「解析并导入」"
                            } else {
                                "已从 PDF 重建 ${outcome.lines.size} 个时段，请核对后再点「解析并导入」"
                            }
                        }

                        outcome.failure != null -> {
                            // 解析器给出的可读中文原因，原样展示，不改写。
                            status = outcome.failure
                        }

                        else -> {
                            status = "PDF 里没有提取到可用文字，可能是扫描件或图片版课表"
                        }
                    }
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
                    // 四个标签横排可能超出窄屏宽度，允许横向滚动，保证「PDF 课表」始终可达。
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

            if (kind == ImportKind.PDF) {
                item(key = "pick-pdf") {
                    Column {
                        Button(
                            onClick = {
                                // OpenDocument：只筛 PDF，由系统文件选择器返回 content:// uri。
                                pickPdf.launch(arrayOf("application/pdf"))
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "选择 PDF 文件")
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
                            // PDF 模式下文本已由重建结果回填，按 TEXT 走既有文本解析流程。
                            // （MainViewModel 的 PDF 分支只用于兜底报错，界面不应走到它。）
                            val parseKind = if (kind == ImportKind.PDF) ImportKind.TEXT else kind
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
                    text = "注意：导入时「同名同教师」的记录会合并为一门课程，同一门课的多个时段会挂在" +
                        "同一门课下；完全相同的时段会自动去重，因此重复导入同一份课表不会产生重复数据。",
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
