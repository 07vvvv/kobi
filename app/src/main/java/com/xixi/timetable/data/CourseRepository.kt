package com.xixi.timetable.data

import android.content.Context
import androidx.room.withTransaction
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.importer.ImportedCourse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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
                // 时段一律重新分配主键与 courseId，避免沿用旧 id 导致主键冲突（契约 8.13）
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

    /** 批量删除（契约 9.5）：调用方可能传入同一门课程的多个时段，先按 courseId 去重；单事务 */
    suspend fun deleteCourses(courseIds: List<Long>) {
        val ids = courseIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return
        database().withTransaction {
            for (id in ids) {
                dao.deleteSessionsOf(id)
            }
            dao.deleteCoursesByIds(ids)
        }
    }

    /** 一键重置（契约 9.5）：清空全部课程与时段，保留 kobi_settings 等配置；单事务 */
    suspend fun resetAll() {
        database().withTransaction {
            dao.deleteAllSessions()
            dao.deleteAllCourses()
        }
    }

    /** 读取单个课程的完整数据，供编辑页回填 */
    suspend fun loadCourse(courseId: Long): Pair<CourseEntity?, List<SessionEntity>> =
        dao.courseOnce(courseId) to dao.sessionsOnce(courseId)

    /**
     * 批量导入（契约 9.1），单事务：
     * 1. 批内 name + teacher 均相同的记录合并为一门课程，每条记录是一个时段；
     * 2. 库内已有同名同教师（导入项教师为空时仅按 name 匹配）的课程则追加时段，不新建；
     * 3. 完全相同的时段（星期 + 起止节次 + 周次 + 地点）去重；
     * 4. 返回值为「新增的课程数」，不含仅被追加时段的既有课程。
     */
    suspend fun importCourses(items: List<ImportedCourse>): Int {
        val valid = items.filter { it.name.isNotBlank() }
        if (valid.isEmpty()) return 0

        // 库内已有课程索引：比较前统一忽略大小写与首尾空白
        val existingByKey = HashMap<String, CourseEntity>()
        val existingByName = HashMap<String, CourseEntity>()
        val existingByNameNoTeacher = HashMap<String, CourseEntity>()
        for (course in dao.observeCourses().first()) {
            val nameKey = normalizeKey(course.name)
            if (nameKey.isEmpty()) continue
            val teacherKey = normalizeKey(course.teacher)
            if (teacherKey.isEmpty()) {
                existingByNameNoTeacher.putIfAbsent(nameKey, course)
            } else {
                existingByKey.putIfAbsent("$nameKey\u0000$teacherKey", course)
            }
            existingByName.putIfAbsent(nameKey, course)
        }

        // 批内合并：同一 key 的记录归入同一门课程
        val groups = LinkedHashMap<String, ArrayList<ImportedCourse>>()
        for (item in valid) {
            val nameKey = normalizeKey(item.name)
            if (nameKey.isEmpty()) continue
            val teacherKey = normalizeKey(item.teacher)
            val key = if (teacherKey.isEmpty()) nameKey else "$nameKey\u0000$teacherKey"
            groups.getOrPut(key) { ArrayList() }.add(item)
        }

        return database().withTransaction {
            var created = 0
            for (groupItems in groups.values) {
                val first = groupItems.first()
                val name = first.name.trim()
                val teacher = first.teacher.trim()
                val nameKey = normalizeKey(name)
                val teacherKey = normalizeKey(teacher)

                val existing = if (teacherKey.isEmpty()) {
                    existingByNameNoTeacher[nameKey] ?: existingByName[nameKey]
                } else {
                    existingByKey["$nameKey\u0000$teacherKey"]
                }

                val courseId = if (existing != null) {
                    existing.id
                } else {
                    // 新建课程：按已创建数量轮转取色（契约 9.1 第 4 条）
                    val newId = dao.insertCourse(
                        CourseEntity(
                            name = name,
                            teacher = teacher,
                            colorIndex = created % PALETTE_SIZE,
                            note = ""
                        )
                    )
                    created++
                    newId
                }

                // 既有课程的时段已存在，需与库内去重；新建课程只需批内去重
                val seen = HashSet<String>()
                if (existing != null) {
                    for (session in dao.sessionsOnce(courseId)) {
                        seen.add(sessionKey(session))
                    }
                }
                val toInsert = ArrayList<SessionEntity>()
                for (item in groupItems) {
                    if (!seen.add(sessionKey(item))) continue
                    toInsert.add(
                        SessionEntity(
                            courseId = courseId,
                            dayOfWeek = item.dayOfWeek,
                            startSection = item.startSection,
                            endSection = item.endSection,
                            location = item.location.trim(),
                            weekPattern = item.weekPattern
                        )
                    )
                }
                if (toInsert.isNotEmpty()) {
                    dao.insertSessions(toInsert)
                }
            }
            created
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
        /** 配色数量，与 ui 层 CoursePalette 的调色板容量一致（契约 8.14：Palette 12 色，此处沿用 10） */
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

        /** 比较用归一化：忽略大小写与首尾空白（契约 9.1） */
        private fun normalizeKey(value: String): String = value.trim().lowercase()

        /** 时段去重键（契约 9.1 第 3 条）：星期 + 起止节次 + 周次 + 地点 */
        private fun sessionKey(session: SessionEntity): String =
            "${session.dayOfWeek}|${session.startSection}|${session.endSection}|${session.weekPattern}|${normalizeKey(session.location)}"

        private fun sessionKey(item: ImportedCourse): String =
            "${item.dayOfWeek}|${item.startSection}|${item.endSection}|${item.weekPattern}|${normalizeKey(item.location)}"
    }
}
