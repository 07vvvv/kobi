package com.xixi.timetable.data.layout

import com.xixi.timetable.data.importer.TextImporter
import com.xixi.timetable.data.week.OddEven
import com.xixi.timetable.data.week.WeekPatterns
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 课表网格重建解析器（契约 §10.3）。
 *
 * 背景：课表的「星期」在表头、「节次」在最左列、课程文字在网格内部，三者物理上不在同一行，
 * 因此把定位文本按出现顺序拼起来逐行解析必然得到 0 条。本类改为**按坐标重建网格**：
 *
 * 1. 星期表头行 → 列中心（保留星期号；缺列时按主间距插值补列）；
 * 2. 最左列节次标签行 → 行中心（缺失时按 y 聚类兜底）；
 * 3. 同一列内按 y 间隙 / `★` 前缀 / 「块内已有周次而新行不再含周次」切块，一块 = 一个时段；
 * 4. 块与节次带按**纵向重叠长度**匹配，得到节次范围（溢出文字不会把单节撑成连堂）；
 * 5. 块内逐项提取课程名 / 教师 / 地点 / 周次，**任意一项失败都不丢块**（逐项兜底）；
 * 6. 网格建不起来时降级为可解析的视觉行（VISUAL_LINES），否则回填原文（RAW）。
 *
 * 本类为纯 Kotlin，零 `android.*` 依赖，可被 JVM 单元测试直接覆盖。
 */
object TimetableLayoutParser {

    /** 解析模式。 */
    enum class Mode { GRID, VISUAL_LINES, RAW }

    /**
     * @param lines 可直接交 `TextImporter` 成功解析的文本行（GRID 为规范化文本，VISUAL_LINES 为视觉行拼接）。
     *              语义保证：**非空 ⟺ 其中每一行都能被 `TextImporter` 解析成功**。
     *              重建失败时必为空列表（此时 `mode` 为 [Mode.RAW]，界面据此回填原始文字）。
     * @param mode 本次重建采用的模式
     * @param sourceLineCount 输入的定位文本条数（用于区分「源头没有文字」与「未能识别出课表结构」）
     * @param droppedLineCount 降级路径中被丢弃（无法被解析）的视觉行数；GRID 与 RAW 恒为 0
     *
     * 另有一个可选入参 `cellBands`：来自 PDF 表格线的「格子行带」。传空时行为与从前完全一致
     * （纯靠文字位置推断节次）；非空时用它精确定节次，见 [CellBand]。
     */
    data class Result(
        val lines: List<String>,
        val mode: Mode,
        val sourceLineCount: Int,
        val droppedLineCount: Int = 0
    )

    /**
     * 来自 PDF 表格网格线的**格子行带**：某个星期列里的一个（可能由多行合并而成的）格子。
     *
     * 为什么要它：课表里一个格子只放一门课的四行元数据（课程名 / 课程号 / 学分教师 / 周次地点）。
     * 实测真实教务课表 PDF（2026-10 抽样，横向 A4）：字号 11pt、格子内行距 14pt，
     * 四行之间三个间隔合计 **42pt**，整块含字身约 53pt；而节次行高只有约 **30pt** ——
     * 也就是说**一个格子的文字总高接近两节课**。所以「拿块的纵向范围去套节次带」会把
     * 单节课普遍误判成连堂，文字里也没有节数信息。而表格线是几何事实：
     * **合并格子的中间那条横线不会被画出来**，所以「哪条边界线存在」直接给出了格子的行跨度。
     *
     * @param weekday 星期几（1~7）
     * @param top 格子纵向范围的上边（内部坐标，y 向下）
     * @param bottom 格子纵向范围的下边
     * @param startSection 该格子对应的起始节次
     * @param endSection 该格子对应的结束节次
     */
    data class CellBand(
        val weekday: Int,
        val top: Int,
        val bottom: Int,
        val startSection: Int,
        val endSection: Int
    )

    // ===================== 参数 =====================

    /** 认定网格模式所需的最少**识别到的**星期列数（插值补出的列不计） */
    private const val MIN_WEEKDAY_COLUMNS = 3

    /** 节次上限（契约 8.7 的作息表为 1~12 节） */
    private const val MAX_SECTION = 12

    /** 纵向间隙超过「行高中位数 × 该系数」时开新块 */
    private const val GAP_FACTOR = 1.6

    /** y 聚类阈值下限：字高极小时避免把同一格的堆叠行拆散 */
    private const val MIN_CLUSTER_GAP = 8.0

    /** 表头只有一列时的兜底列宽（内部坐标尺度约 150） */
    private const val DEFAULT_COLUMN_SPACING = 150

    /** 课程名彻底提取不出来时的占位（仍然输出该行） */
    private const val UNKNOWN_COURSE = "未识别课程"

    /** 下边界 = 最后一个节次行中心 + 行距中位数 × 该系数 */
    private const val LOWER_BOUND_FACTOR = 1.2

    /** 节次行少于 2 个时的行距兜底（内部坐标尺度约 57） */
    private const val FALLBACK_ROW_HEIGHT = 57.0

    /**
     * 节次范围：重叠长度达到「主重叠 × 该系数」的节次带才纳入范围。
     *
     * 取 0.7 而不是 0.6：格子文字向下溢出时，单节块与下一节次带的重叠比常落在 0.55~0.7，
     * 而真正跨两节的连堂块几乎覆盖两个带、比值接近 1.0。0.6 的余量只有约 1px
     * （溢出再多一点点就会把单节课误判成连堂）；0.8 则会漏判「真连堂但块略偏上」（比值约 0.8）。
     * 注意：溢出极端严重（比值 > 0.95）时与真连堂在几何上不可分，此时判成连堂是可接受的误差。
     */
    private const val OVERLAP_FACTOR = 0.7

    /** 相邻已识别列中心间距最多按该倍数插值补列，防御异常数据 */
    private const val MAX_INTERPOLATED_COLUMNS = 3

    /**
     * 页眉页脚噪声关键词：整行命中即排除。
     * 这些词出现在教务课表的页眉（学期 / 学号 / 姓名 / 班级 / 学分）、页脚（备注）与工具栏上，
     * 不会出现在课程格子里；不排除的话它们会被当成课程入库，用户会凭空多出几门课。
     */
    private val NOISE_KEYWORDS = arrayOf(
        "课程表", "学期", "学号", "姓名", "班级", "学分", "备注", "教务处", "打印", "导出"
    )

    // ===================== 正则 =====================

    /** 星期表头：星期一 / 周一 / 礼拜天 */
    private val WEEKDAY_RE = Regex("""^(?:星期|周|礼拜)\s*([一二三四五六日天1-7])$""")

    /** 节次标签：`3` / `第3节` */
    private val SECTION_SINGLE_RE = Regex("""^第?\s*(\d{1,2})\s*节?$""")

    /** 节次标签：`3-4节` */
    private val SECTION_RANGE_RE = Regex("""^第?\s*(\d{1,2})\s*[-~—～至]\s*(\d{1,2})\s*节?$""")

    /** 节次标签：`08:00`（无节次号时按出现顺序编号） */
    private val TIME_LABEL_RE = Regex("""^\d{1,2}\s*[:：]\s*\d{2}$""")

    /** 块首强制信号：★ 在不同来源里可能写成 `*` `☆` `※` */
    private val BULLET_RE = Regex("""^\s*[★☆*※▪●]""")

    /** 清洗课程名时剥离的块首符号 */
    private val BULLET_STRIP_RE = Regex("""^\s*[★☆*※▪●]+\s*""")

    // 周次片段识别：**直接复用** `TextImporter` 里的那一份（单一来源），本文件不再保留副本。
    // 该正则同时用于切块信号与地点锚点，必须与周次解析口径完全一致：曾经复制过副本，
    // 结果上面「地点数字不得吞掉星期」的两条断言只修了 TextImporter 侧、副本漏修，两处行为漂移。

    /** 教师：`/中文2~4字/`（右侧斜杠可能被换行隔开） */
    private val SLASH_TEACHER_RE = Regex("""/\s*([\u4e00-\u9fa5]{2,4})\s*/""")

    /** 汉字字符（地点弱特征用：含汉字的短串不按「含数字」判为地点） */
    private val HAN_CHAR_RE = Regex("""[\u4e00-\u9fa5]""")

    /** 教师特征：2~4 个汉字 */
    private val HAN_NAME_RE = Regex("""^[\u4e00-\u9fa5]{2,4}$""")

    /** 教师特征：X老师 / X教授 */
    private val TEACHER_TITLE_RE = Regex("""^[\u4e00-\u9fa5]{1,4}(?:老师|教授)$""")

    /**
     * 周次片段之后的前导标点（`】,` `],` `),` 等）。
     *
     * 不能直接用 `[】\])…]` 去锚定地点：课程号 `(0000000000)000000000` 里的 `)` 会被误当结束符，
     * 于是地点被错认成课程号。这里改为以**周次片段**为锚点取其后第一段。
     */
    private val LEADING_PUNCT_RE = Regex("""^(?:[\s】\]})）〕,，.、·:：]|[（(]\s*[单双]\s*[)）])+""")

    /** 地点特征（沿用契约 9.2 第 1 条） */
    private val PLACE_KEYWORDS = arrayOf("楼", "馆", "室", "区", "栋", "阶", "场", "中心")

    private val ALNUM_PLACE_RE = Regex("""^[A-Za-z]+\d+[A-Za-z]?$""")

    private val PURE_DIGIT_RE = Regex("""^\d+$""")

    /** 课程号形态：`(长数字)尾随数字`，如 `(0000000000)000000000` */
    private val CODE_GROUP_RE = Regex("""[（(]\s*\d{4,}\s*[)）]\s*\d*""")

    /** 独立成片段的学分括号：`(3)` */
    private val CODE_STANDALONE_RE = Regex("""^\(\d{1,4}\)$""")

    /** 长数字串（课程号 / 单位代码） */
    private val LONG_DIGIT_RE = Regex("""\d{6,}""")

    /** 纯数字或小数（学分、课序号） */
    private val NUMERIC_ONLY_RE = Regex("""^\d{1,6}(?:[.．]\d{1,3})?$""")

    /** 纯小数（学分） */
    private val DECIMAL_ONLY_RE = Regex("""^\d+[.．]\d+$""")

    /** 行尾孤立数字（`…\n 005` / `…\n2.0`）；前面必须有空白或括号，避免误伤「大学英语2」 */
    private val TRAILING_LOOSE_NUMBER_RE = Regex("""[\s（(]+\d{1,6}(?:[.．]\d{1,3})?[\s)）]*$""")

    /** 片段切分：`/` `|` `,` 顿号 分号 换行 */
    private val SEGMENT_SPLIT_RE = Regex("""[/|,，、;；\n\r]+""")

    /** 连续空白 */
    private val WHITESPACE_RE = Regex("""\s+""")

    // ===================== 入口 =====================

    fun parse(lines: List<PositionedText>, cellBands: List<CellBand> = emptyList()): Result {
        val sourceLineCount = lines.size
        val cleaned = lines.filter { it.text.isNotBlank() }
        if (cleaned.isEmpty()) {
            return Result(emptyList(), Mode.RAW, sourceLineCount)
        }

        // 1) 星期表头列：按星期号去重后按 x 排序；缺列时按主间距插值补列
        val detectedHeaders = cleaned
            .mapNotNull { line -> weekdayIndexOf(line.text)?.let { weekday -> weekday to line } }
            .groupBy { it.first }
            .map { (weekday, entries) -> weekday to entries.first().second }
            .sortedBy { it.second.centerX }
        val columns = buildColumns(detectedHeaders)
        val columnCenters = columns.map { it.centerX }
        val firstColumnBoundary = firstColumnBoundary(columnCenters)
        val headerBottom = detectedHeaders.maxOfOrNull { it.second.bottom }

        // 2) 网格内容行：统一剔除最左节次列、星期表头、页眉页脚、表头之上的内容。
        //    buildSectionRows 的 y 聚类兜底与 buildGridRows 必须共用这一份内容。
        val baseContent = cleaned.filter { isGridContent(it, firstColumnBoundary, headerBottom) }

        // 3) 字号取**网格内容行**的中位数：页眉页脚的大字号不得抬高切块阈值，
        //    否则同格两门课的间隙不再超阈值，会被并成一块丢掉一门课。
        val fallbackHeight = medianOf(cleaned.map { it.height.toDouble() }).coerceAtLeast(1.0)
        val averageHeight = if (baseContent.isEmpty()) {
            fallbackHeight
        } else {
            medianOf(baseContent.map { it.height.toDouble() }).coerceAtLeast(1.0)
        }

        val hasGrid = detectedHeaders.size >= MIN_WEEKDAY_COLUMNS && columnCenters.isNotEmpty()

        // 4) 节次行：优先用最左列的节次标签，缺失时按 y 聚类兜底
        val sectionLabelLines = if (hasGrid) {
            cleaned
                .filter { it.centerX < firstColumnBoundary && sectionIndexCandidate(it.text) != null }
                .sortedBy { it.centerY }
        } else {
            emptyList()
        }

        // 5) 网格模式：只要列与行都定得下来就产出，不要求字段全部提取成功
        if (hasGrid) {
            val sectionRows = buildSectionRows(sectionLabelLines, baseContent, averageHeight)
            if (sectionRows.isNotEmpty()) {
                val content = applyLowerBound(baseContent, sectionRows, sectionLabelLines.isNotEmpty())
                val rows = buildGridRows(content, columns, sectionRows, averageHeight, cellBands)
                if (rows.isNotEmpty()) {
                    // GRID 已产出行就不要再跑降级路径，避免同一时段出现两遍
                    return Result(rows, Mode.GRID, sourceLineCount)
                }
            }
        }

        // 6) 降级：视觉行（行内按 x 排序拼接）。只保留能被 `TextImporter` 解析的行，
        //    以维持 [Result.lines] 的语义保证（非空 ⟺ 每行都能解析成功）；
        //    被丢弃的行数放进 droppedLineCount，界面据此提示「有 N 行未能识别」，
        //    不能静默丢掉一部分却仍然报「已重建 N 个时段」。
        val visual = visualLines(cleaned, averageHeight)
        val parseable = visual.filter { line ->
            val parsed = TextImporter.parse(line)
            parsed.items.isNotEmpty() && parsed.errors.isEmpty()
        }
        if (parseable.isEmpty()) {
            return Result(emptyList(), Mode.RAW, sourceLineCount)
        }
        return Result(parseable, Mode.VISUAL_LINES, sourceLineCount, visual.size - parseable.size)
    }

    // ===================== 网格构建 =====================

    /** 网格列：中心 x + 星期号（缺列时插值补出的列，星期号取左右邻列的中点） */
    private class GridColumn(val centerX: Int, val weekday: Int)

    /** 节次行：节次号 + 纵向中心 */
    private class SectionRow(val index: Int, val centerY: Int)

    /** 一行是否属于网格内部（排除最左节次列、星期表头、页眉页脚、表头之上的内容） */
    private fun isGridContent(line: PositionedText, firstColumnBoundary: Int, headerBottom: Int?): Boolean {
        if (line.centerX < firstColumnBoundary) return false
        if (weekdayIndexOf(line.text) != null) return false
        if (isNoiseLine(line.text)) return false
        if (headerBottom != null && line.centerY <= headerBottom) return false
        return true
    }

    /** 页眉 / 页脚关键词判定 */
    private fun isNoiseLine(text: String): Boolean =
        NOISE_KEYWORDS.any { text.contains(it) }

    /** 下边界之后（页脚）的内容剔除；无节次标签时不设下边界，只靠关键词兜底 */
    private fun applyLowerBound(
        content: List<PositionedText>,
        sectionRows: List<SectionRow>,
        hasSectionLabels: Boolean
    ): List<PositionedText> {
        if (!hasSectionLabels) return content
        val last = sectionRows.lastOrNull() ?: return content
        val lowerBound = last.centerY + (rowSpacingOf(sectionRows) * LOWER_BOUND_FACTOR).toInt()
        return content.filter { it.centerY <= lowerBound }
    }

    /** 第一列（星期列）左边界：小于它的内容属于最左的节次列 */
    private fun firstColumnBoundary(columnCenters: List<Int>): Int {
        if (columnCenters.isEmpty()) return Int.MIN_VALUE
        if (columnCenters.size == 1) return columnCenters[0] - DEFAULT_COLUMN_SPACING / 2
        val spacing = columnCenters[1] - columnCenters[0]
        return columnCenters[0] - maxOf(spacing / 2, 1)
    }

    /**
     * 由已识别的星期表头构造列：若相邻两列的间距接近主间距的整数倍，说明中间**缺了列**
     * （源头漏掉了某个「星期X」），就在中间插值补出列中心，星期号按左右邻列线性插值。
     *
     * 否则缺列一侧的内容会被最近中心吸走，整列错位到相邻星期。
     */
    private fun buildColumns(detected: List<Pair<Int, PositionedText>>): List<GridColumn> {
        if (detected.isEmpty()) return emptyList()
        if (detected.size == 1) {
            return listOf(GridColumn(detected[0].second.centerX, detected[0].first))
        }
        val gaps = (1 until detected.size).map {
            (detected[it].second.centerX - detected[it - 1].second.centerX).toDouble()
        }
        val mainSpacing = medianOf(gaps)
        val result = ArrayList<GridColumn>()
        result.add(GridColumn(detected[0].second.centerX, detected[0].first))
        for (i in 1 until detected.size) {
            val prev = detected[i - 1]
            val current = detected[i]
            val gap = (current.second.centerX - prev.second.centerX).toDouble()
            val missing = if (mainSpacing > 0.0) {
                ((gap / mainSpacing).roundToInt() - 1).coerceIn(0, MAX_INTERPOLATED_COLUMNS)
            } else {
                0
            }
            for (k in 1..missing) {
                val centerX = prev.second.centerX + (gap * k / (missing + 1)).roundToInt()
                val weekday = prev.first + ((current.first - prev.first) * k) / (missing + 1)
                if (weekday in 1..7) result.add(GridColumn(centerX, weekday))
            }
            result.add(GridColumn(current.second.centerX, current.first))
        }
        return result
    }

    private fun buildSectionRows(
        labels: List<PositionedText>,
        baseContent: List<PositionedText>,
        averageHeight: Double
    ): List<SectionRow> {
        if (labels.isNotEmpty()) {
            val parsed = labels.map { line ->
                SectionRow(sectionIndexCandidate(line.text) ?: 0, line.centerY)
            }.sortedBy { it.centerY }
            // 节次号解析不出来（如只有 HH:MM）时，退化为「第几个节次标签行」
            return parsed.mapIndexed { position, row ->
                if (row.index in 1..MAX_SECTION) row else SectionRow(position + 1, row.centerY)
            }
        }
        // 无节次标签：把网格内容行按 y 聚成「视觉行带」，带序号即节次（相对节次）。
        // 判据是**相邻两行**的 centerY 差（不是与带首比较），否则同一格里行距均匀的堆叠文字
        // 会因为累计偏移而被拆成多个带，节次范围随之被撑大。
        if (baseContent.isEmpty()) return emptyList()
        val threshold = maxOf(averageHeight * GAP_FACTOR, MIN_CLUSTER_GAP)
        val bands = ArrayList<Pair<Int, MutableList<PositionedText>>>()
        var previousCenterY: Int? = null
        for (line in baseContent.sortedBy { it.centerY }) {
            val previous = previousCenterY
            if (previous == null || line.centerY - previous > threshold) {
                bands.add(line.centerY to arrayListOf(line))
            } else {
                bands.last().second.add(line)
            }
            previousCenterY = line.centerY
        }
        return bands.mapIndexed { position, band ->
            val centerY = band.second.sumOf { it.centerY } / band.second.size
            // 兜底路径的带序号就是「相对第几节」；上限收敛到 MAX_SECTION，
            // 否则内容行很多时会产出「第13-13节」——网格页只画 12 节，该时段会从界面上消失。
            SectionRow((position + 1).coerceAtMost(MAX_SECTION), centerY)
        }
    }

    private fun buildGridRows(
        content: List<PositionedText>,
        columns: List<GridColumn>,
        sectionRows: List<SectionRow>,
        averageHeight: Double,
        cellBands: List<CellBand>
    ): List<String> {
        if (content.isEmpty() || columns.isEmpty()) return emptyList()
        val columnCenters = columns.map { it.centerX }
        val threshold = maxOf(averageHeight * GAP_FACTOR, MIN_CLUSTER_GAP)
        val byColumn = content.groupBy { nearestColumn(columnCenters, it.centerX) }

        val output = ArrayList<String>()
        for (columnIndex in byColumn.keys.sorted()) {
            val weekday = columns.getOrNull(columnIndex)?.weekday ?: (columnIndex + 1)
            val columnLines = byColumn.getValue(columnIndex).sortedBy { it.centerY }
            for (block in splitIntoBlocks(columnLines, threshold)) {
                output.add(buildRow(block, weekday, sectionRows, cellBands))
            }
        }
        return output
    }

    /** 最近列中心（等价于按相邻列中心的中点划区间） */
    private fun nearestColumn(columnCenters: List<Int>, centerX: Int): Int {
        var bestIndex = 0
        var bestDistance = Int.MAX_VALUE
        for (index in columnCenters.indices) {
            val distance = abs(columnCenters[index] - centerX)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    /**
     * 切块：间隙超阈值、行以 `★` 开头、或「块内已出现过周次而该行不再含周次」时开新块。
     *
     * 第三个信号用于兜住 `★` 丢失且两门课纵向紧邻的情形：课表里周次行
     * （`/【周次】,地点`）是每格的最后一行，因此「周次之后又出现新内容」即意味着进入下一门课；
     * 但周次行与地点行本身可能被拆成两行，这时不能误切（见 [isBlockTailContinuation]）。
     */
    private fun splitIntoBlocks(sorted: List<PositionedText>, threshold: Double): List<List<PositionedText>> {
        if (sorted.isEmpty()) return emptyList()
        val blocks = ArrayList<List<PositionedText>>()
        var current = ArrayList<PositionedText>()
        var previous: PositionedText? = null
        var currentHasWeek = false
        for (line in sorted) {
            val gap = if (previous == null) 0.0 else (line.top - previous.bottom).toDouble()
            val startsWithBullet = BULLET_RE.containsMatchIn(line.text)
            val hasWeek = TextImporter.WEEK_FRAGMENT_RE.containsMatchIn(line.text)
            // 该行本身可能只是本块尾部被拆开的延续（周次行之后的地点行），这时不能开新块
            val continuesBlock = isBlockTailContinuation(line)
            val startNew = current.isNotEmpty() &&
                (gap > threshold || startsWithBullet || (currentHasWeek && !hasWeek && !continuesBlock))
            if (startNew) {
                blocks.add(current)
                current = ArrayList()
                currentHasWeek = false
            }
            current.add(line)
            if (hasWeek) currentHasWeek = true
            previous = line
        }
        if (current.isNotEmpty()) blocks.add(current)
        return blocks
    }

    /**
     * 该行是否只是本块尾部（周次行 / 地点行）被拆开的延续：
     * 纯标点开头、纯数字、或命中地点特征时视为延续，不开新块 ——
     * 否则会多出一条只有地点、课程名为「未识别课程」的假课。
     */
    private fun isBlockTailContinuation(line: PositionedText): Boolean {
        val text = line.text.trim()
        if (text.isEmpty()) return true
        if (text.first() in ",，.、;；:：|/】])）·") return true
        val normalized = normalizeForMatch(text)
        if (PURE_DIGIT_RE.matches(normalized)) return true
        return isPlaceLike(text)
    }

    private fun buildRow(
        block: List<PositionedText>,
        weekday: Int,
        sectionRows: List<SectionRow>,
        cellBands: List<CellBand>
    ): String {
        val blockText = block.sortedBy { it.top }.joinToString("\n") { it.text }

        // 逐项独立兜底：任何一项提取失败都不允许丢块
        val nameCandidate = extractCourseNameCandidate(blockText)
        val teacher = extractTeacher(blockText, nameCandidate)
        val location = extractLocation(blockText, nameCandidate, teacher)
        val name = if (nameCandidate.isNotEmpty()) {
            nameCandidate
        } else {
            longestSegment(blockText, teacher, location)
        }.ifEmpty { UNKNOWN_COURSE }

        val weekMask = extractWeekMask(blockText)
            ?: WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, OddEven.ALL)

        val range = sectionRange(block, weekday, sectionRows, cellBands)
        val day = weekdayName(weekday)

        val line = "${cleanField(name)},${cleanField(teacher)},${cleanField(location)},$day," +
            "第${range.first}-${range.second}节,${weekField(weekMask)}"

        // 硬验收：产物必须能被 TextImporter 解析；万一不能，退回纯骨架行（仍保留星期与节次）
        val parsed = TextImporter.parse(line)
        if (parsed.items.isNotEmpty() && parsed.errors.isEmpty()) return line
        val fallback = WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, OddEven.ALL)
        return "$UNKNOWN_COURSE,,,$day,第${range.first}-${range.second}节,${weekField(fallback)}"
    }

    /**
     * 块的节次范围：每个节次带取 `[center − rowSpacing/2, center + rowSpacing/2]`，
     * 按**纵向重叠长度**找出主节次，再把重叠达到「主重叠 × [OVERLAP_FACTOR]」的带纳入范围（取 min~max）。
     *
     * 用整体 `[minTop, maxBottom]` 的旧口径会被格子溢出文字带偏：单节块只要越过下一节的行中心
     * 就会被算成连堂；而真实连堂（块高≈2×rowSpacing）依然会命中两个带。
     */
    private fun sectionRange(
        block: List<PositionedText>,
        weekday: Int,
        sectionRows: List<SectionRow>,
        cellBands: List<CellBand>
    ): Pair<Int, Int> {
        // 优先用表格线给的格子行带：它是几何事实，能正确区分「单节课文字溢出」与「真正的连堂」。
        // 一个格子的四行元数据总高常超过一节课的行高，只看块的纵向范围会把单节课普遍误判成连堂；
        // 表格线里合并格子不画中间那条横线，「哪条边界线存在」才是节次数量的精确来源。
        // 判据是「**块的顶边**最接近哪个格子的**上边界**」，而不是「块心落在哪个区间」：
        // 课表格子里的文字是从格子左上角开始往下排的，四行元数据的总高又常常超过格子高度、
        // 向下溢出，于是块心会明显偏离格子中心，只有块顶才稳定贴着格顶。
        if (cellBands.isNotEmpty()) {
            val anchor = block.minOfOrNull { it.top }
            if (anchor != null) {
                val band = cellBands
                    .filter { it.weekday == weekday }
                    .minByOrNull { abs(it.top - anchor) }
                if (band != null) return band.startSection to maxOf(band.startSection, band.endSection)
            }
        }
        if (sectionRows.isEmpty()) return 1 to 1
        val top = block.minOf { it.top }.toDouble()
        val bottom = block.maxOf { it.bottom }.toDouble()
        val half = rowSpacingOf(sectionRows) / 2.0
        val overlaps = sectionRows.map { row ->
            val bandTop = row.centerY - half
            val bandBottom = row.centerY + half
            val overlap = minOf(bottom, bandBottom) - maxOf(top, bandTop)
            row.index to overlap
        }
        val best = overlaps.maxByOrNull { it.second }
        if (best == null || best.second <= 0.0) {
            val middle = (top + bottom) / 2.0
            val nearest = sectionRows.minByOrNull { abs(it.centerY - middle) } ?: sectionRows.first()
            return nearest.index to nearest.index
        }
        val threshold = best.second * OVERLAP_FACTOR
        val selected = overlaps.filter { it.second >= threshold }
        val start = selected.minOf { it.first }
        val end = selected.maxOf { it.first }
        return start to maxOf(start, end)
    }

    // ===================== 字段提取 =====================

    /** 课程名：`★` 后到第一个 `/` 的一段，剔除课程号/学分编号 */
    private fun extractCourseNameCandidate(text: String): String {
        val head = text.replaceFirst(BULLET_STRIP_RE, "").substringBefore('/')
        val stripped = cleanField(stripCodes(head))
        if (!hasLetterOrDigit(stripped)) return ""
        return stripped
    }

    /** 教师：优先 `/中文2~4字/`，否则退回 2~4 汉字片段（排除课程名） */
    private fun extractTeacher(text: String, nameCandidate: String): String {
        SLASH_TEACHER_RE.find(text)?.let { match ->
            val value = cleanField(match.groupValues[1])
            if (value.isNotEmpty()) return value
        }
        for (segment in splitSegments(text)) {
            val cleaned = cleanField(segment)
            if (cleaned.isEmpty() || cleaned == nameCandidate) continue
            val normalized = normalizeForMatch(cleaned)
            if (normalized.isEmpty() || isCodeLike(cleaned)) continue
            if (HAN_NAME_RE.matches(normalized) || TEACHER_TITLE_RE.matches(normalized)) return cleaned
        }
        return ""
    }

    /**
     * 地点：以周次片段为锚点取其后第一段；取不到时退回末尾一个像地点的片段。
     *
     * 教学楼短串、纯数字（3301）、中文+数字（室外1）、纯中文（在线教学）都要能兜住。
     */
    private fun extractLocation(text: String, nameCandidate: String, teacher: String): String {
        val weekMatch = TextImporter.WEEK_FRAGMENT_RE.find(text)
        if (weekMatch != null) {
            val candidate = firstSegmentAfterPunctuation(text.substring(weekMatch.range.last + 1))
            if (candidate.isNotEmpty() && !isStrongCode(candidate)) return candidate
        }
        val segments = splitSegments(text).map { cleanField(it) }.filter { it.isNotEmpty() }
        for (segment in segments.asReversed()) {
            if (segment == nameCandidate || segment == teacher) continue
            if (isStrongCode(segment) || TextImporter.WEEK_FRAGMENT_RE.containsMatchIn(segment)) continue
            if (isPlaceLike(segment)) return segment
        }
        return ""
    }

    /** 跳过前导标点后取第一段，用于「周次之后就是地点」的定位 */
    private fun firstSegmentAfterPunctuation(tail: String): String {
        val trimmed = LEADING_PUNCT_RE.replace(tail, "")
        if (trimmed.isEmpty()) return ""
        val firstLine = trimmed.substringBefore('\n')
        return cleanField(SEGMENT_SPLIT_RE.split(firstLine).firstOrNull().orEmpty())
    }

    /** 课程名最终兜底：块内最长的、非编号非噪声片段 */
    private fun longestSegment(text: String, teacher: String, location: String): String {
        val candidates = splitSegments(text)
            .map { cleanField(stripCodes(it)) }
            .filter { it.isNotEmpty() && hasLetterOrDigit(it) }
            .filter { it != teacher && it != location }
            .filter { !isCodeLike(it) && !TextImporter.WEEK_FRAGMENT_RE.containsMatchIn(it) }
        return candidates.maxByOrNull { it.length } ?: ""
    }

    /**
     * 周次掩码：直接委托 `TextImporter.parseWeekPatternText`（逻辑单点，避免两处口径漂移）。
     *
     * 它能整段抓取含「周」的片段（`1-3(单),6.8-9周` 不丢段）、逐片段绑定单双、
     * 并兼容「只有括号不含『周』字」（`【1-16】`）的写法。
     */
    private fun extractWeekMask(text: String): Long? = TextImporter.parseWeekPatternText(text)

    /**
     * 周次字段的序列化：优先用 `WeekPatterns.describe`（契约 10.3 第 7 条）。
     *
     * 但 `describe` 对多区间会输出 `1-4,6-16周`，能否交回 TextImporter 正确解析取决于它的实现，
     * 因此用 `TextImporter.parseWeekPatternText` 做一次回环校验；不一致才退化为逐周枚举。
     */
    private fun weekField(mask: Long): String {
        val described = WeekPatterns.describe(mask)
        if (TextImporter.parseWeekPatternText(described) == mask) return described
        val weeks = WeekPatterns.weeksOf(mask)
        if (weeks.isEmpty()) return "1-${WeekPatterns.MAX_WEEKS}周"
        return weeks.joinToString(",") + "周"
    }

    // ===================== 文本处理 =====================

    /** 视觉行：按 centerY 聚类，行内按 x 排序用空格连接 */
    private fun visualLines(lines: List<PositionedText>, averageHeight: Double): List<String> {
        if (lines.isEmpty()) return emptyList()
        val threshold = maxOf(averageHeight * GAP_FACTOR, MIN_CLUSTER_GAP)
        val result = ArrayList<String>()
        var bucket = ArrayList<PositionedText>()
        var lastCenterY = Int.MIN_VALUE
        for (line in lines.sortedBy { it.centerY }) {
            if (bucket.isNotEmpty() && abs(line.centerY - lastCenterY) > threshold) {
                result.add(joinBucket(bucket))
                bucket = ArrayList()
            }
            bucket.add(line)
            lastCenterY = line.centerY
        }
        if (bucket.isNotEmpty()) result.add(joinBucket(bucket))
        return result.filter { it.isNotBlank() }
    }

    private fun joinBucket(bucket: List<PositionedText>): String =
        bucket.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()

    /** 字段清洗：替换分隔符、删除空白，避免被 TextImporter 的 SEPARATOR_RE 二次拆开 */
    private fun cleanField(raw: String): String {
        var text = toHalfWidth(raw)
        text = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        text = text.replace(',', '·').replace('，', '·').replace('、', '·')
        text = WHITESPACE_RE.replace(text, "")
        return text.trim('·', ' ', '\u3000')
    }

    /** 全角转半角（括号、数字、字母），便于统一比较与剔除 */
    private fun toHalfWidth(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            when {
                code == 0x3000 -> builder.append(' ')
                code in 0xFF01..0xFF5E -> builder.append((code - 0xFEE0).toChar())
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /** 比较用归一化：转半角 + 去掉所有空白 */
    private fun normalizeForMatch(segment: String): String =
        WHITESPACE_RE.replace(toHalfWidth(segment), "")

    /** 剔除课程号：`(长数字)尾随数字` 与行尾孤立数字 */
    private fun stripCodes(raw: String): String {
        var text = CODE_GROUP_RE.replace(raw, "")
        text = TRAILING_LOOSE_NUMBER_RE.replace(text, "")
        return text
    }

    private fun splitSegments(text: String): List<String> =
        SEGMENT_SPLIT_RE.split(text).map { it.trim() }.filter { it.isNotEmpty() }

    private fun hasLetterOrDigit(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /** 中位数（间距、字号统计共用） */
    private fun medianOf(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** 相邻节次行中心的间距中位数；节次行不足 2 个时用固定行高兜底 */
    private fun rowSpacingOf(sectionRows: List<SectionRow>): Double {
        if (sectionRows.size < 2) return FALLBACK_ROW_HEIGHT
        val gaps = (1 until sectionRows.size).map {
            (sectionRows[it].centerY - sectionRows[it - 1].centerY).toDouble()
        }
        val spacing = medianOf(gaps)
        return if (spacing > 0.0) spacing else FALLBACK_ROW_HEIGHT
    }

    /** 严格编号判定：用于课程名候选过滤 */
    private fun isCodeLike(segment: String): Boolean {
        val normalized = normalizeForMatch(segment)
        if (normalized.isEmpty()) return false
        if (isStrongCode(segment)) return true
        if (NUMERIC_ONLY_RE.matches(normalized)) return true
        return CODE_STANDALONE_RE.matches(normalized)
    }

    /** 强编号：括号长数字、6 位以上数字、纯小数（学分） */
    private fun isStrongCode(segment: String): Boolean {
        val normalized = normalizeForMatch(segment)
        if (normalized.isEmpty()) return false
        if (LONG_DIGIT_RE.containsMatchIn(normalized)) return true
        return DECIMAL_ONLY_RE.matches(normalized)
    }

    /** 地点特征（契约 9.2 第 1 条） */
    private fun isPlaceLike(segment: String): Boolean {
        if (PLACE_KEYWORDS.any { segment.contains(it) }) return true
        val normalized = normalizeForMatch(segment)
        if (ALNUM_PLACE_RE.matches(normalized)) return true
        if (PURE_DIGIT_RE.matches(normalized)) return true
        if (normalized.contains("教") && normalized.any { it.isDigit() }) return true
        // 弱特征：长度 ≤ 12、不含汉字且含数字（如「3-101」「101A」）。
        // 比 TextImporter.isPlaceLike 的「以数字结尾」更宽松 —— 这里定位的是「地点」，
        // 宁可多认一个候选，也不能把地点漏成空；含汉字的串（如「大学英语2」）一律不判为地点。
        return normalized.length <= 12 &&
            !HAN_CHAR_RE.containsMatchIn(normalized) &&
            normalized.any { it.isDigit() }
    }

    // ===================== 星期 =====================

    private val WEEKDAY_CHARS = arrayOf("一", "二", "三", "四", "五", "六", "日")

    /** 文本是否是星期表头；是则返回 1~7 */
    internal fun weekdayIndexOf(text: String): Int? {
        val match = WEEKDAY_RE.find(text.trim()) ?: return null
        val token = match.groupValues[1]
        if (token == "日" || token == "天") return 7
        val index = WEEKDAY_CHARS.indexOf(token)
        if (index >= 0) return index + 1
        val number = token.toIntOrNull() ?: return null
        return if (number in 1..7) number else null
    }

    /** 星期号（1=周一 … 7=周日）→ 文案 */
    private fun weekdayName(weekday: Int): String =
        "周" + WEEKDAY_CHARS[(weekday - 1).coerceIn(0, WEEKDAY_CHARS.size - 1)]

    /** 节次标签解析：`3` / `第3节` / `3-4节` / `08:00`（时间标签返回 0，交给顺序编号） */
    private fun sectionIndexCandidate(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        SECTION_RANGE_RE.find(trimmed)?.let { match ->
            val start = match.groupValues[1].toIntOrNull()
            if (start != null && start in 1..MAX_SECTION) return start
        }
        SECTION_SINGLE_RE.find(trimmed)?.let { match ->
            val value = match.groupValues[1].toIntOrNull()
            if (value != null && value in 1..MAX_SECTION) return value
        }
        if (TIME_LABEL_RE.matches(trimmed)) return 0
        return null
    }
}
