package com.xixi.timetable.data

import android.content.Context
import androidx.room.withTransaction
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.importer.ImportedCourse
import kotlinx.coroutines.flow.Flow

/**
 * 课程仓库：界面层与提醒层的唯一数据入口。
 *
 * 写操作方法内部使用 Room 事务（`withTransaction`），保证「课程 + 其全部时段」的一致性。
 */
class CourseRepository(private val dao: CourseDao) {

    val courses: Flow<List<CourseEntity>> = dao.observeCourses()

    val sessions: Flow<List<SessionEntity>> = dao.observeSessions()

    /** 全量保存（新建或更新）：写入课程并整体替换其所有时段，单事务 */
    suspend fun saveCourse(course: CourseEntity, sessions: List<SessionEntity>): Long =
        database().withTransaction {
            val courseId = if (course.id == 0L) {
                dao.insertCourse(course)
            } else {
                dao.updateCourse(course)
                course.id
            }
            dao.deleteSessionsOf(courseId)
            if (sessions.isNotEmpty()) {
                // 时段一律重新分配主键与 courseId，避免沿用旧 id 导致主键冲突
                dao.insertSessions(sessions.map { it.copy(id = 0L, courseId = courseId) })
            }
            courseId
        }

    /** 删除课程及其时段（外键 CASCADE；此处显式删除时段，不依赖 PRAGMA 开关） */
    suspend fun deleteCourse(courseId: Long) {
        database().withTransaction {
            dao.deleteSessionsOf(courseId)
            dao.deleteCourseById(courseId)
        }
    }

    /** 读取单个课程的完整数据，供编辑页回填 */
    suspend fun loadCourse(courseId: Long): Pair<CourseEntity?, List<SessionEntity>> =
        dao.courseOnce(courseId) to dao.sessionsOnce(courseId)

    /** 批量导入，单事务；返回成功写入的课程数 */
    suspend fun importCourses(items: List<ImportedCourse>): Int {
        val valid = items.filter { it.name.isNotBlank() }
        if (valid.isEmpty()) return 0
        return database().withTransaction {
            var written = 0
            for (item in valid) {
                val courseId = dao.insertCourse(
                    CourseEntity(
                        name = item.name.trim(),
                        teacher = item.teacher.trim(),
                        colorIndex = written % PALETTE_SIZE,
                        note = ""
                    )
                )
                dao.insertSessions(
                    listOf(
                        SessionEntity(
                            courseId = courseId,
                            dayOfWeek = item.dayOfWeek,
                            startSection = item.startSection,
                            endSection = item.endSection,
                            location = item.location.trim(),
                            weekPattern = item.weekPattern
                        )
                    )
                )
                written++
            }
            written
        }
    }

    /** 供提醒模块使用：读取当前全部时段（挂起，一次性） */
    suspend fun allSessions(): List<SessionEntity> = dao.allSessionsOnce()

    /** 取数据库实例：事务入口；未初始化属于编程错误，此处明确报错而非静默降级 */
    private fun database(): AppDatabase =
        AppDatabase.currentOrNull()
            ?: throw IllegalStateException(
                "AppDatabase 尚未初始化，请通过 CourseRepository.get(context) 创建仓库实例"
            )

    companion object {
        /** 配色数量，与 ui 层 CoursePalette 的调色板容量一致（契约要求至少 10 种） */
        private const val PALETTE_SIZE: Int = 10

        @Volatile
        private var instance: CourseRepository? = null

        /** 进程内单例 */
        fun get(context: Context): CourseRepository {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val cached = instance
                if (cached != null) {
                    cached
                } else {
                    // 先初始化数据库单例，再构造仓库：事务入口依赖 AppDatabase.currentOrNull()
                    val created = CourseRepository(AppDatabase.get(context).courseDao())
                    instance = created
                    created
                }
            }
        }
    }
}
