package com.xixi.timetable.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 课程与时段的数据访问对象。
 *
 * 只包含契约中声明的方法；事务边界由 CourseRepository 负责。
 */
@Dao
interface CourseDao {

    @Query("SELECT * FROM courses ORDER BY name ASC")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM sessions ORDER BY dayOfWeek ASC, startSection ASC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun courseById(id: Long): CourseEntity?

    @Query("SELECT * FROM sessions WHERE courseId = :courseId ORDER BY dayOfWeek, startSection")
    suspend fun sessionsOf(courseId: Long): List<SessionEntity>

    @Insert
    suspend fun insertCourse(course: CourseEntity): Long

    @Update
    suspend fun updateCourse(course: CourseEntity)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourseById(id: Long)

    @Insert
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions WHERE courseId = :courseId")
    suspend fun deleteSessionsOf(courseId: Long)

    @Query("DELETE FROM courses")
    suspend fun deleteAllCourses()

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM courses WHERE id IN (:ids)")
    suspend fun deleteCoursesByIds(ids: List<Long>)

    @Query("SELECT * FROM sessions")
    suspend fun allSessionsOnce(): List<SessionEntity>

    /**
     * 全部课程（挂起，一次性）。
     * 用途：批量导入时**在事务内**构建「库内已有课程」索引，避免读—写竞态（契约 9.1）。
     */
    @Query("SELECT * FROM courses")
    suspend fun allCoursesOnce(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun courseOnce(id: Long): CourseEntity?

    @Query("SELECT * FROM sessions WHERE courseId = :courseId")
    suspend fun sessionsOnce(courseId: Long): List<SessionEntity>

    @Insert
    suspend fun insertCourses(courses: List<CourseEntity>): List<Long>
}
