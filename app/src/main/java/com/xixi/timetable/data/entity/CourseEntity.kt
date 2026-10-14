package com.xixi.timetable.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 课程（科目）实体。
 *
 * 一门课程可以对应多条时段记录（SessionEntity），时段通过 courseId 外键关联到本表。
 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val teacher: String = "",
    val colorIndex: Int = 0,
    val note: String = ""
)
