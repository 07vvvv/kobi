package com.xixi.timetable.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xixi.timetable.data.CourseRepository
import com.xixi.timetable.data.conflict.Conflict
import com.xixi.timetable.data.conflict.ConflictDetector
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.importer.CsvImporter
import com.xixi.timetable.data.importer.IcsImporter
import com.xixi.timetable.data.importer.ImportResult
import com.xixi.timetable.data.importer.TextImporter
import com.xixi.timetable.notify.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 导入来源。
 */
enum class ImportKind { CSV, TEXT, ICS }

/**
 * 界面层唯一的数据入口。
 *
 * 只做三件事：把仓库的 Flow 转成 StateFlow、把写操作丢到 viewModelScope、写完提醒层重排。
 * 不做任何业务规则判断（冲突判定交给 ConflictDetector）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: CourseRepository = CourseRepository.get(app)

    /** 全部课程，按名称升序（由 DAO 决定）。 */
    val courses: StateFlow<List<CourseEntity>> = repository.courses.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    /** 全部时段。 */
    val sessions: StateFlow<List<SessionEntity>> = repository.sessions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    /** 全量时段的两两冲突，供网格页与编辑页提示。 */
    val conflicts: StateFlow<List<Conflict>> = repository.sessions
        .map { list -> ConflictDetector.findConflicts(list) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    /**
     * 保存课程及其全部时段（新建或覆盖更新）。
     * course.id 为 0 表示新建，由数据层分配主键并回填到时段上。
     * 写库完成后立刻重排提醒，再回调 onDone。
     */
    fun saveCourse(
        course: CourseEntity,
        sessions: List<SessionEntity>,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            repository.saveCourse(course, sessions)
            rescheduleReminders()
            onDone()
        }
    }

    /** 删除课程及其全部时段，并重排提醒。 */
    fun deleteCourse(courseId: Long) {
        viewModelScope.launch {
            repository.deleteCourse(courseId)
            rescheduleReminders()
        }
    }

    /** 读取单个课程的完整数据用于编辑回填。 */
    fun loadCourse(courseId: Long, onLoaded: (CourseEntity?, List<SessionEntity>) -> Unit) {
        viewModelScope.launch {
            val (course, sessionList) = repository.loadCourse(courseId)
            onLoaded(course, sessionList)
        }
    }

    /**
     * 解析文本并批量导入。
     * 解析失败的行会作为 errors 原样回传给界面，不做静默丢弃。
     */
    fun importFromText(text: String, kind: ImportKind, onResult: (ImportResult) -> Unit) {
        viewModelScope.launch {
            val result: ImportResult = when (kind) {
                ImportKind.CSV -> CsvImporter.parse(text)
                ImportKind.TEXT -> TextImporter.parse(text)
                ImportKind.ICS -> IcsImporter.parse(text)
            }
            if (result.items.isNotEmpty()) {
                repository.importCourses(result.items)
                rescheduleReminders()
            }
            onResult(result)
        }
    }

    /** 重排全部提醒。调度器内部自行切换后台线程，不会阻塞主线程。 */
    fun rescheduleReminders() {
        ReminderScheduler.rescheduleAll(getApplication())
    }
}
