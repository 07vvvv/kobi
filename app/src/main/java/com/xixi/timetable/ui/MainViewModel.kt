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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 导入来源。
 *
 * 顺序与导入页的来源标签严格一一对应（契约 9.6）：
 * CSV 表格 / 粘贴文本 / ICS 日历 / PDF 课表。
 *
 * [PDF] 不参与 [MainViewModel.importFromText] 的文本解析分派：PDF 课表由导入页先选择文件、
 * 重建为规范化文本并回填到粘贴框，用户核对后仍按 [TEXT] 解析。此处保留该枚举值是为了让
 * 来源标签与枚举取同一份顺序，并让分派 [when] 保持穷尽。
 */
enum class ImportKind { CSV, TEXT, ICS, PDF }

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

    /**
     * 全量时段的两两冲突，供网格页与编辑页提示。
     *
     * 冲突检测是纯 CPU 计算（两两比较），显式切到 Default 调度器，
     * 避免首屏订阅时在主线程上算（契约 9.7：首屏不得做全量重计算）。
     */
    val conflicts: StateFlow<List<Conflict>> = repository.sessions
        .map { list -> ConflictDetector.findConflicts(list) }
        .flowOn(Dispatchers.Default)
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

    /**
     * 批量删除课程及其全部时段（列表页单条删除与多选删除共用）。
     *
     * 重复的 courseId 在此去重——列表页按「时段」勾选，同一门课的多个时段会重复出现
     * （契约 9.5），不去重会导致同一课程被删多次。
     */
    fun deleteCourses(courseIds: List<Long>) {
        val distinct = courseIds.filter { it > 0L }.distinct()
        if (distinct.isEmpty()) return
        viewModelScope.launch {
            repository.deleteCourses(distinct)
            rescheduleReminders()
        }
    }

    /**
     * 一键重置：清空全部课程与时段，**保留**学期起始日等设置（契约 9.5）。
     * 清空后立即重排提醒，消除已排的全量闹钟，再回调 onDone。
     */
    fun resetAll(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.resetAll()
            rescheduleReminders()
            onDone()
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
                // PDF 课表不经过本方法：导入页先选择 PDF 文件并重建为规范化文本，
                // 再由用户核对后按 TEXT 解析。该分支若被真正走到，说明界面接线有误，
                // 明确报错而不是静默失败。
                ImportKind.PDF -> ImportResult(
                    items = emptyList(),
                    errors = listOf("PDF 课表需先在导入页选择文件并重建，结果会回填到文本框后再解析")
                )
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
