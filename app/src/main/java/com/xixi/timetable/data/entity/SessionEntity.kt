package com.xixi.timetable.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 上课时段实体。
 *
 * 删除课程时其时段通过外键 CASCADE 一并删除。
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = CourseEntity::class,
        parentColumns = ["id"],
        childColumns = ["courseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("courseId")]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    val dayOfWeek: Int,      // 1=周一 … 7=周日
    val startSection: Int,   // 起始节次，1-based
    val endSection: Int,     // 结束节次，1-based，含端点
    val location: String = "",
    val weekPattern: Long    // 周次位掩码：bit0 表示第 1 周
)
