package com.xixi.timetable.data.layout

/**
 * 一段带矩形坐标的定位文本：`text` 是内容，四个 Int 是它在**页面坐标系**中的矩形
 * （原点在左上角，x 向右、y **向下**增长）。
 *
 * 坐标是课表网格重建的唯一依据：课表的「星期」在表头、「节次」在最左列，课程文字在
 * 网格内部，三者物理上不在同一行 —— 只有拿到坐标才能把它们还原成「某门课在星期几
 * 的第几节」。
 *
 * 数据来源是 PDF 内容流：`PdfTimetableImporter` 把 PDF 用户空间（原点在左下角、
 * y 向上）翻转成本类型的左上角原点坐标，并按固定倍数放大取整，使 PDF 的 pt 尺度
 * 与 [TimetableLayoutParser] 里那一套按图片像素调出来的阈值兼容。
 *
 * 该类型为纯 Kotlin 数据类，不依赖 `android.*`，因此可被 JVM 单元测试直接构造，
 * 从而在不依赖真机与任何文件的前提下覆盖解析逻辑。
 */
data class PositionedText(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    /** 水平中心（列归属用） */
    val centerX: Int get() = (left + right) / 2

    /** 垂直中心（行归属用） */
    val centerY: Int get() = (top + bottom) / 2

    /** 文本高度，至少为 1（行高统计用） */
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}
