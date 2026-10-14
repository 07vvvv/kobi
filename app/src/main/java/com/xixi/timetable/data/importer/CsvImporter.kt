package com.xixi.timetable.data.importer

import com.xixi.timetable.data.week.WeekPatterns

/**
 * CSV 导入器。
 *
 * 表头顺序：课程名,教师,地点,星期,起始节次,结束节次,周次
 *
 * 首行若为表头（课程名 / 课程名称 / name）自动跳过；空行与空列宽容处理；
 * 任何无法解析的行写入 [ImportResult.errors]，不抛异常。
 */
object CsvImporter {

    /** 契约要求的列数 */
    private const val COLUMN_COUNT: Int = 7

    fun parse(text: String): ImportResult {
        val items = ArrayList<ImportedCourse>()
        val errors = ArrayList<String>()
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        var headerChecked = false
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val columns = line.split(',').map { it.trim().trim('"') }
            if (!headerChecked) {
                headerChecked = true
                if (isHeader(columns)) continue
            }
            val lineNo = index + 1
            if (columns.size < COLUMN_COUNT) {
                errors.add("第${lineNo}行：需要 $COLUMN_COUNT 列（课程名,教师,地点,星期,起始节次,结束节次,周次），实际 ${columns.size} 列")
                continue
            }
            val name = columns[0]
            if (name.isEmpty()) {
                errors.add("第${lineNo}行：课程名为空")
                continue
            }
            val dayOfWeek = columns[3].toIntOrNull()
            if (dayOfWeek == null || dayOfWeek !in 1..7) {
                errors.add("第${lineNo}行：星期「${columns[3]}」无效，应为 1-7")
                continue
            }
            val startSection = columns[4].toIntOrNull()
            val endSection = columns[5].toIntOrNull()
            if (startSection == null || endSection == null || startSection < 1 || endSection < startSection) {
                errors.add("第${lineNo}行：节次「${columns[4]}-${columns[5]}」无效")
                continue
            }
            val weekPattern = WeekPatterns.parseText(columns[6])
            if (weekPattern == null) {
                errors.add("第${lineNo}行：周次「${columns[6]}」无法解析")
                continue
            }
            items.add(
                ImportedCourse(
                    name = name,
                    teacher = columns[1],
                    location = columns[2],
                    dayOfWeek = dayOfWeek,
                    startSection = startSection,
                    endSection = endSection,
                    weekPattern = weekPattern
                )
            )
        }
        return ImportResult(items = items, errors = errors)
    }

    /** 首行是否表头 */
    private fun isHeader(columns: List<String>): Boolean {
        if (columns.isEmpty()) return false
        val first = columns[0]
        return first == "课程名" || first == "课程名称" || first.equals("name", ignoreCase = true)
    }
}
