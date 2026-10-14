package com.xixi.timetable.data.importer

/**
 * 导入过程中的中间课程：一门课程 + 一个时段。
 *
 * 解析器只产出该结构，落库由 CourseRepository.importCourses 负责。
 */
data class ImportedCourse(
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weekPattern: Long
)
