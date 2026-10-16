package com.xixi.timetable.data.pdf

/**
 * PDF 内容流中的一个文本项：一次 `Tj` / `TJ` 展示操作产生的字符串，附带它在
 * **PDF 用户空间**里的起始坐标与字号（单位 pt）。
 *
 * 坐标约定（与屏幕坐标相反，务必注意）：
 * - 原点在页面**左下角**，x 向右、y **向上**增长；
 * - [x] 是文本基线起点的横坐标；
 * - [y] 是**基线**的纵坐标（不是文字顶边）；
 * - [fontSize] 是该文本项生效的字号（`Tf` 的第 2 个操作数，单位 pt）。
 *
 * 本类型为纯 Kotlin 数据类，不依赖 `android.*`，因此可被 JVM 单元测试直接构造，
 * 从而在不依赖真机的前提下覆盖 PDF 解析与课表网格重建逻辑。
 */
data class PdfTextItem(
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float
)

/**
 * PDF 内容流里的一条**直线段**（来自 `m` / `l` 路径被 `S` 描边的那一次绘制）。
 *
 * 坐标同样是 PDF 用户空间（原点左下角、y 向上）。
 *
 * 课表的表格网格线就是靠这些线段还原的：**合并单元格中间的那条横线不会被画出来**，
 * 于是「某一列的哪些行边界线存在」直接给出了每个格子的行跨度 —— 也就是课程的节次范围。
 * 仅靠文字位置推不出连堂课程占几节，这是唯一精确的信息源。
 */
data class PdfLineSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

/**
 * 一页 PDF 的文本项与表格线段。
 *
 * @param width 页面 MediaBox 宽度（pt）
 * @param height 页面 MediaBox 高度（pt）
 * @param items 该页全部文本项，按内容流中的出现顺序排列
 * @param segments 该页全部描边直线段；解析器不收集线段时为空列表，
 *                 课表重建便会退化为「只靠文字位置推断节次」的降级路径
 */
data class PdfPageText(
    val width: Float,
    val height: Float,
    val items: List<PdfTextItem>,
    val segments: List<PdfLineSegment> = emptyList()
)

/**
 * PDF 无法解析时抛出（结构损坏、加密、不支持的过滤器或编码）。
 *
 * 调用方（导入页）必须捕获本异常，把它转成可读提示，**不得**让界面崩溃。
 */
class PdfParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
