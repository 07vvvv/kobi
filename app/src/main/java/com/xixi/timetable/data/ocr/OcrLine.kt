package com.xixi.timetable.data.ocr

/**
 * OCR 识别出的一行文字，及其在图片中的位置（像素，左上角为原点）。
 *
 * 坐标是课表网格重建的唯一依据：课表截图的「星期」在表头、「节次」在最左列，
 * 课程文字在网格内部，三者物理上不在同一行 —— 只有拿到坐标才能把它们还原成
 * 「某门课在星期几的第几节」。
 *
 * 该类型为纯 Kotlin 数据类，不依赖 `android.*`，因此可被 JVM 单元测试直接构造，
 * 从而在不依赖真机与图像的前提下覆盖解析逻辑。
 */
data class OcrLine(
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
