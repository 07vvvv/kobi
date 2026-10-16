package com.xixi.timetable.data.pdf

import com.xixi.timetable.data.layout.PositionedText
import com.xixi.timetable.data.layout.TimetableLayoutParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF 课表导入结果。
 *
 * @param lines 可直接交 `TextImporter` 解析的规范化文本行。成功时非空，
 *              且**每一行都能被 `TextImporter` 解析成功**（由 [TimetableLayoutParser] 保证）。
 * @param sourceLineCount PDF 里提取到的文本项总数，用于区分「PDF 根本没有文字」与
 *                        「有文字但重建不出课表结构」这两种不同的失败原因。
 * @param droppedLineCount 重建过程中被丢弃、未能识别的行数（0 表示没有丢弃）。
 * @param failure 失败时的可读中文原因；成功时为 `null`。
 */
data class PdfImportResult(
    val lines: List<String>,
    val sourceLineCount: Int,
    val droppedLineCount: Int,
    val failure: String?
)

/**
 * PDF 课表导入：把 PDF 字节还原成「可直接解析的课表文本行」。
 *
 * 处理链路（每一步都在本类或它的下游有明确职责）：
 *
 * 1. [PdfTextExtractor] 解出每一页的**带坐标文本项**与**表格描边线段**
 *    （PDF 用户空间，原点在左下角、y 向上）；
 * 2. 本类把逐字定位的文本项**按基线聚行、按水平间隙切片段**，还原成一行行可读文字；
 * 3. 同时把坐标**翻转为左上角原点**（y 向下）并放大 [COORDINATE_SCALE] 倍取整，
 *    因为 [TimetableLayoutParser] 里那一套阈值是按课表图片的像素尺度调出来的；
 * 4. 本类再由表格线算出**格子行带**（见 [TimetableLayoutParser.CellBand]）：课表里合并
 *    单元格中间那条横线不会被画出来，所以「哪条边界线存在」直接给出格子的行跨度，
 *    也就是课程的节次范围 —— 这是唯一能正确区分「单节课文字溢出」与「真正连堂」的信息；
 * 5. [TimetableLayoutParser] 按坐标重建「节次 × 星期」网格，输出规范化文本行。
 *
 * 为什么必须走坐标而不是直接读文字：课表的「星期」在表头、「节次」在最左列，课程文字在
 * 网格内部，三者物理上不在同一行 —— 把 PDF 文字按出现顺序拼起来逐行解析必然得到 0 条。
 *
 * 本类为纯 Kotlin，零 `android.*` 依赖，可被 JVM 单元测试直接覆盖。
 */
object PdfTimetableImporter {

    /**
     * PDF 的 pt 坐标 → 内部坐标系的放大倍数。
     *
     * 选 1.5 是为了让 PDF 的物理尺度对齐 [TimetableLayoutParser] 里按图片像素调出的阈值：
     * 教务课表 PDF 的星期列宽约 106pt、节次行高约 30pt、正文字号约 11pt，
     * 乘 1.5 后约为 159 / 45 / 16.5，与样张图片的 150 / 57 / 18 基本一致。
     *
     * 注意：本常量与 [TimetableLayoutParser] 的门限是一对耦合关系，改它必须复核那些阈值。
     */
    private const val COORDINATE_SCALE = 1.5f

    /** 两条文本项基线纵坐标之差在「字号 × 该系数」以内视为同一行 */
    private const val SAME_LINE_TOLERANCE = 0.35f

    /**
     * 同一行内相邻两项的水平间隙超过「字号 × 该系数」就切成两个独立片段。
     *
     * 取 0.65：同行相邻字符的间隙通常接近 0（逐字定位时前一项右边界正好等于后一项起点），
     * 而跨列文字与列内不同要素之间的间隙远大于一个字宽。阈值太小会把「课 程 名」逐字拆散，
     * 太大则会把相邻两列粘成一段。
     */
    private const val SEGMENT_GAP_FACTOR = 0.65f

    /** 间隙超过「字号 × 该系数」时在拼接处补一个空格，避免「05杨昕」这类粘连 */
    private const val SPACE_GAP_FACTOR = 0.22f

    /** 字号兜底：PDF 里 Tf 未设置或异常时按 1pt 处理，避免出现 0 或负数 */
    private const val MIN_FONT_SIZE = 1f

    /** 判定一条线段是水平/垂直的坐标容差（pt） */
    private const val AXIS_TOLERANCE = 0.5f

    /** 参与网格重建的最短线段长度（pt），用于抹掉页眉页脚与边框的装饰短线 */
    private const val MIN_GRID_SEGMENT_LENGTH = 12f

    /** 两个坐标值之差在该容差（内部坐标）以内即视为同一条网格线或同一个格子的边界 */
    private const val LINE_MERGE_TOLERANCE = 3

    /**
     * 认定为一「列」的最小宽度（内部坐标）。
     *
     * 真实课表最左侧的节次列宽约 40pt（×1.5 = 60），星期列宽约 106pt（×1.5 = 159），
     * 因此用 100 把节次列与它的边框线挡掉，只留下真正的星期列。
     */
    private const val MIN_COLUMN_WIDTH = 100

    /** 认定为一个可行带（格子）的最小高度（内部坐标），排除退化出来的细缝 */
    private const val MIN_BAND_HEIGHT = 8

    /** 节次上限（与 [TimetableLayoutParser] 的作息表口径一致） */
    private const val MAX_SECTION = 12

    /**
     * 解析 PDF 字节并重建课表。
     *
     * 本函数**绝不抛异常**：任何失败都转成 [PdfImportResult.failure] 里的一句可读中文。
     * 界面据此给出提示，绝不允许因为一份坏 PDF 让应用崩掉。
     */
    fun parse(bytes: ByteArray): PdfImportResult {
        val pages = try {
            PdfTextExtractor.extractAllPages(bytes)
        } catch (e: PdfParseException) {
            return PdfImportResult(emptyList(), 0, 0, e.message ?: "这个 PDF 无法解析")
        } catch (t: Throwable) {
            // 提取器承诺只抛 PdfParseException，这里再兜一层，防御实现细节异常泄漏到界面。
            val reason = t.message ?: t::class.java.simpleName
            return PdfImportResult(emptyList(), 0, 0, "PDF 解析失败：$reason")
        }

        if (pages.isEmpty()) {
            return PdfImportResult(emptyList(), 0, 0, "这个 PDF 里没有找到任何页面")
        }

        val sourceCount = pages.sumOf { page -> page.items.size }
        if (sourceCount == 0) {
            return PdfImportResult(
                lines = emptyList(),
                sourceLineCount = 0,
                droppedLineCount = 0,
                failure = "这个 PDF 是扫描件或图片版课表：里面没有可提取的文字，无法重建课表"
            )
        }

        // 逐页尝试：课表通常只有一页，多页时取第一个能重建出网格的页面，
        // 避免把封面、说明页的文字混进课表。
        for (page in pages) {
            val observedTop = observedTopOf(page) ?: continue
            val positioned = toPositionedText(page, observedTop)
            if (positioned.isEmpty()) continue
            val cellBands = buildCellBands(page, positioned, observedTop)
            val rebuilt = TimetableLayoutParser.parse(positioned, cellBands)
            if (rebuilt.lines.isNotEmpty()) {
                return PdfImportResult(
                    lines = rebuilt.lines,
                    sourceLineCount = sourceCount,
                    droppedLineCount = rebuilt.droppedLineCount,
                    failure = null
                )
            }
        }

        return PdfImportResult(
            lines = emptyList(),
            sourceLineCount = sourceCount,
            droppedLineCount = 0,
            failure = "已从 PDF 提取到 $sourceCount 处文字，但没能识别出课表结构：" +
                "这个 PDF 可能不是「节次 × 星期」网格样式的课表"
        )
    }

    // ===================== 文本项 → 定位文本 =====================

    /**
     * 观测到的文字顶边（PDF 坐标，y 最大处就是页面上方）；没有可用文字时返回 `null`。
     *
     * 用它代替页面高度做坐标翻转，这样即使 PDF 缺少 MediaBox 或做了页面旋转，
     * 相对坐标依然自洽。
     */
    private fun observedTopOf(page: PdfPageText): Float? {
        var top = Float.NEGATIVE_INFINITY
        for (item in page.items) {
            if (item.text.isBlank()) continue
            val candidate = item.y + fontSizeOf(item)
            if (candidate > top) top = candidate
        }
        return if (top.isFinite()) top else null
    }

    /**
     * 把一页 PDF 的文本项还原成一行行带坐标的定位文本。
     *
     * 两步：
     * 1. **按基线聚行** —— 逐字定位的 PDF 里每个字都是一条独立文本项，同一行文字的基线
     *    纵坐标相同（或相差极小），按「字号 × [SAME_LINE_TOLERANCE]」的容差聚成一行；
     * 2. **按水平间隙切片段** —— 同一行里可能横跨多个表格列，列与列之间有明显间隙，
     *    间隙超过「字号 × [SEGMENT_GAP_FACTOR]」就切开，保证一个片段只属于一个格子。
     */
    private fun toPositionedText(page: PdfPageText, observedTop: Float): List<PositionedText> {
        val items = page.items.filter { it.text.isNotBlank() }
        if (items.isEmpty()) return emptyList()

        // 1) 按基线 y 聚行。items 按 y 降序（PDF 里 y 越大越靠上），同行的项自然相邻。
        val rows = ArrayList<MutableList<PdfTextItem>>()
        val baselines = ArrayList<Float>()
        for (item in items.sortedByDescending { it.y }) {
            val last = rows.size - 1
            if (last >= 0 && abs(baselines[last] - item.y) <= fontSizeOf(item) * SAME_LINE_TOLERANCE) {
                rows[last].add(item)
            } else {
                rows.add(mutableListOf(item))
                baselines.add(item.y)
            }
        }

        // 2) 行内按 x 排序后切片段
        val result = ArrayList<PositionedText>()
        for (rowIndex in rows.indices) {
            val sorted = rows[rowIndex].sortedBy { it.x }
            val baselineY = baselines[rowIndex]
            var segment = ArrayList<PdfTextItem>()
            var segmentText = StringBuilder()
            var cursorRight = Float.NEGATIVE_INFINITY

            for (item in sorted) {
                if (segment.isEmpty()) {
                    segment.add(item)
                    segmentText.append(item.text)
                    cursorRight = item.x + widthOf(item)
                    continue
                }
                val gap = item.x - cursorRight
                val reference = fontSizeOf(item)
                if (gap > reference * SEGMENT_GAP_FACTOR) {
                    // 间隙过大：当前片段到此为止，item 另起一段
                    emit(result, segment, segmentText, baselineY, observedTop)
                    segment = ArrayList()
                    segmentText = StringBuilder()
                    segment.add(item)
                    segmentText.append(item.text)
                    cursorRight = item.x + widthOf(item)
                } else {
                    if (gap > reference * SPACE_GAP_FACTOR) segmentText.append(' ')
                    segmentText.append(item.text)
                    segment.add(item)
                    cursorRight = max(cursorRight, item.x + widthOf(item))
                }
            }
            if (segment.isNotEmpty()) {
                emit(result, segment, segmentText, baselineY, observedTop)
            }
        }
        return result
    }

    /** 把一个片段落成 [PositionedText]：横向取片段的最小左边界与最大右边界。 */
    private fun emit(
        out: MutableList<PositionedText>,
        segment: List<PdfTextItem>,
        text: StringBuilder,
        baselineY: Float,
        observedTop: Float
    ) {
        if (segment.isEmpty()) return
        val content = text.toString().trim()
        if (content.isEmpty()) return

        var left = Float.MAX_VALUE
        var right = Float.NEGATIVE_INFINITY
        var fontSize = MIN_FONT_SIZE
        for (item in segment) {
            if (item.x < left) left = item.x
            val r = item.x + widthOf(item)
            if (r > right) right = r
            val f = fontSizeOf(item)
            if (f > fontSize) fontSize = f
        }
        if (right <= left) right = left + fontSize

        // PDF（原点左下、y 向上）→ 页面（原点左上、y 向下）
        val top = ((observedTop - baselineY - fontSize) * COORDINATE_SCALE).roundToInt()
        val bottom = ((observedTop - baselineY) * COORDINATE_SCALE).roundToInt()
        val leftScaled = (left * COORDINATE_SCALE).roundToInt()
        val rightScaled = (right * COORDINATE_SCALE).roundToInt()

        out.add(
            PositionedText(
                text = content,
                left = leftScaled,
                top = top,
                right = if (rightScaled > leftScaled) rightScaled else leftScaled + 1,
                bottom = if (bottom > top) bottom else top + 1
            )
        )
    }

    // ===================== 表格线 → 格子行带 =====================

    /** 内部坐标下的一条水平网格线 */
    private class HorizontalLine(val y: Int, val left: Int, val right: Int)

    /**
     * 由表格线算出每个格子的行跨度，交给 [TimetableLayoutParser] 精确断定节次。
     *
     * 算法：
     * 1. **竖直网格线** → 去重排序后得到列的左右边界；相邻边界之间宽度达到
     *    [MIN_COLUMN_WIDTH] 的才算一列（借此把最左侧的节次列挡在外面）；
     * 2. **列 → 星期几**：用星期表头文字的水平中心落在哪一列来定；表头缺失时按列序兜底；
     * 3. **节次号**：最左列左侧的纯数字文本项（`1`~`12`），它们的垂直中心就是各节次的位置；
     * 4. **水平网格线** → 每一列各自的行边界；相邻两条边界围成一个行带（格子）；
     *    落在同一个行带里的节次号就是这一格覆盖的节次区间 —— 合并单元格中间那条线不会被画，
     *    所以这一步天然得到正确的连堂跨度；
     * 5. 没有节次号落入的行带（例如表头那一行）直接跳过。
     *
     * 任何一步拿不到足够信息就返回空列表，重建器会自动退化为「只靠文字位置推断节次」。
     */
    private fun buildCellBands(
        page: PdfPageText,
        positioned: List<PositionedText>,
        observedTop: Float
    ): List<TimetableLayoutParser.CellBand> {
        val boundaries = verticalBoundaries(page)
        if (boundaries.size < 2) return emptyList()

        val columns = ArrayList<IntArray>()
        for (index in 0 until boundaries.size - 1) {
            val left = boundaries[index]
            val right = boundaries[index + 1]
            if (right - left >= MIN_COLUMN_WIDTH) columns.add(intArrayOf(left, right))
        }
        if (columns.isEmpty()) return emptyList()

        // 列 → 星期几：优先用星期表头文字中心落在哪一列
        val weekdays = IntArray(columns.size) { it + 1 }
        for (line in positioned) {
            val weekday = TimetableLayoutParser.weekdayIndexOf(line.text) ?: continue
            val columnIndex = columns.indexOfFirst { line.centerX >= it[0] && line.centerX <= it[1] }
            if (columnIndex >= 0) weekdays[columnIndex] = weekday
        }

        // 节次号：位于最左列左侧的纯数字
        val firstColumnLeft = columns.first()[0]
        val sectionLabels = ArrayList<Pair<Int, Int>>()
        for (line in positioned) {
            if (line.centerX >= firstColumnLeft) continue
            val section = line.text.trim().toIntOrNull() ?: continue
            if (section in 1..MAX_SECTION) sectionLabels.add(section to line.centerY)
        }
        if (sectionLabels.isEmpty()) return emptyList()

        val horizontals = toHorizontalLines(page, observedTop)
        if (horizontals.isEmpty()) return emptyList()

        val bands = ArrayList<TimetableLayoutParser.CellBand>()
        for (index in columns.indices) {
            val left = columns[index][0]
            val right = columns[index][1]
            val raw = ArrayList<Int>()
            for (line in horizontals) {
                val middle = (line.left + line.right) / 2
                if (middle in left..right) raw.add(line.y)
            }
            raw.sort()

            val bounds = ArrayList<Int>()
            for (y in raw) {
                if (bounds.isEmpty() || y - bounds.last() > LINE_MERGE_TOLERANCE) bounds.add(y)
            }

            for (i in 0 until bounds.size - 1) {
                val top = bounds[i]
                val bottom = bounds[i + 1]
                if (bottom - top < MIN_BAND_HEIGHT) continue
                val sections = sectionLabels
                    .filter { it.second >= top && it.second < bottom }
                    .map { it.first }
                if (sections.isEmpty()) continue
                bands.add(
                    TimetableLayoutParser.CellBand(
                        weekday = weekdays[index],
                        top = top,
                        bottom = bottom,
                        startSection = sections.min(),
                        endSection = sections.max()
                    )
                )
            }
        }
        return bands
    }

    /** 竖直网格线的 x（内部坐标，去重升序） */
    private fun verticalBoundaries(page: PdfPageText): List<Int> {
        val xs = ArrayList<Int>()
        for (segment in page.segments) {
            if (abs(segment.x1 - segment.x2) > AXIS_TOLERANCE) continue
            if (abs(segment.y2 - segment.y1) < MIN_GRID_SEGMENT_LENGTH) continue
            xs.add((segment.x1 * COORDINATE_SCALE).roundToInt())
        }
        xs.sort()
        val merged = ArrayList<Int>()
        for (x in xs) {
            if (merged.isEmpty() || x - merged.last() > LINE_MERGE_TOLERANCE) merged.add(x)
        }
        return merged
    }

    /** 水平网格线（内部坐标） */
    private fun toHorizontalLines(page: PdfPageText, observedTop: Float): List<HorizontalLine> {
        val result = ArrayList<HorizontalLine>()
        for (segment in page.segments) {
            if (abs(segment.y1 - segment.y2) > AXIS_TOLERANCE) continue
            if (abs(segment.x2 - segment.x1) < MIN_GRID_SEGMENT_LENGTH) continue
            val y = ((observedTop - segment.y1) * COORDINATE_SCALE).roundToInt()
            val left = (minOf(segment.x1, segment.x2) * COORDINATE_SCALE).roundToInt()
            val right = (maxOf(segment.x1, segment.x2) * COORDINATE_SCALE).roundToInt()
            if (right <= left) continue
            result.add(HorizontalLine(y, left, right))
        }
        return result
    }

    // ===================== 度量与工具 =====================

    private fun fontSizeOf(item: PdfTextItem): Float =
        if (item.fontSize.isFinite() && item.fontSize >= MIN_FONT_SIZE) item.fontSize else MIN_FONT_SIZE

    /**
     * 估算一个文本项在 PDF 用户空间里的宽度（pt）。
     *
     * PDF 的文本项只给基线起点，没有宽度；而切片段需要宽度才能算间隙。
     * 按「全角字符占 1 个字身、半角字符占 0.5 个字身」估算，精度足够区分
     * 「同一段内相邻字」与「跨列的两个段」——两者间隙相差一个数量级。
     */
    private fun widthOf(item: PdfTextItem): Float {
        val fontSize = fontSizeOf(item)
        var units = 0f
        for (ch in item.text) {
            units += if (isFullWidth(ch)) 1f else 0.5f
        }
        return if (units > 0f) units * fontSize else fontSize
    }

    /** 全角判定：CJK、假名、谚文、全角标点，以及常用装饰符号（★☆◎ 等） */
    private fun isFullWidth(ch: Char): Boolean {
        val code = ch.code
        return code in 0x1100..0x115F ||
            code in 0x2600..0x27BF ||
            code in 0x2E80..0x303E ||
            code in 0x3041..0x33FF ||
            code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xA000..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6
    }
}
