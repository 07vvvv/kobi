package com.xixi.timetable.data.importer

/**
 * 导入结果：成功解析出的条目 + 容错信息。
 *
 * 三个导入器都保证不抛异常：任何无法解析的内容一律写入 [errors]。
 */
data class ImportResult(
    val items: List<ImportedCourse>,
    val errors: List<String>
)
