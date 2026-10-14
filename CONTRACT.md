# kobi 接口契约（CONTRACT）

> **本文件是并行开发的唯一依据。任何实现都必须严格符合此处的包名、类型名、字段名、方法签名。**
> 不得自行更名、不得改变参数顺序、不得新增未声明为 `internal` 的公开类型。

---

## 0. 项目基本信息

| 项 | 值 |
|---|---|
| 应用显示名 | **kobi** |
| namespace / applicationId | `com.xixi.timetable` |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 语言 / UI | Kotlin + Jetpack Compose Material3 |
| 数据库 | Room 2.7.1（KSP） |
| 构建 | AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.9 / JDK 17 |

**约束**：不引入契约以外的第三方依赖；不联网；不使用 WorkManager / DataStore（配置用 SharedPreferences，每日校验用 AlarmManager）。

---

## 1. 文件归属（写入范围，严禁越界）

| 归属 | 路径前缀 |
|---|---|
| **Lead** | 根目录构建文件、`.github/`、`README.md`、`CONTRACT.md`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/**`、`.../timetable/KobiApp.kt`、`.../timetable/MainActivity.kt` |
| **data** | `app/src/main/java/com/xixi/timetable/data/**` |
| **ui** | `app/src/main/java/com/xixi/timetable/ui/**` |
| **remind** | `app/src/main/java/com/xixi/timetable/notify/**` |

---

## 2. 数据层（data 负责实现）

### 2.1 实体

包：`com.xixi.timetable.data.entity`

```kotlin
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val teacher: String = "",
    val colorIndex: Int = 0,
    val note: String = ""
)

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
```

### 2.2 周次工具

包：`com.xixi.timetable.data.week`，文件名 `WeekPatterns.kt`

```kotlin
enum class OddEven { ALL, ODD, EVEN }

object WeekPatterns {
    const val MAX_WEEKS: Int = 30

    /** 由若干周次序号（1-based）构造掩码 */
    fun of(vararg weeks: Int): Long

    /** 由「起止周 + 单双周」构造掩码 */
    fun fromRange(start: Int, end: Int, oddEven: OddEven): Long

    /** 判断某周是否落在掩码内 */
    fun contains(mask: Long, week: Int): Boolean

    /** 两个掩码是否有交集（冲突检测用） */
    fun intersects(a: Long, b: Long): Boolean

    /** 展开为周次列表，升序 */
    fun weeksOf(mask: Long): List<Int>

    /** 人类可读摘要，如「1-16周(单)」「2,4,6周」 */
    fun describe(mask: Long): String
}
```

### 2.3 DAO

包：`com.xixi.timetable.data`，文件名 `CourseDao.kt`

```kotlin
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

    @Query("SELECT * FROM sessions")
    suspend fun allSessionsOnce(): List<SessionEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun courseOnce(id: Long): CourseEntity?

    @Query("SELECT * FROM sessions WHERE courseId = :courseId")
    suspend fun sessionsOnce(courseId: Long): List<SessionEntity>

    @Insert
    suspend fun insertCourses(courses: List<CourseEntity>): List<Long>
}
```

### 2.4 数据库

包：`com.xixi.timetable.data`，文件名 `AppDatabase.kt`

```kotlin
@Database(
    entities = [CourseEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao

    companion object {
        /** 进程内单例；用 applicationContext */
        fun get(context: Context): AppDatabase
    }
}
```

### 2.5 仓库

包：`com.xixi.timetable.data`，文件名 `CourseRepository.kt`

```kotlin
class CourseRepository(private val dao: CourseDao) {
    val courses: Flow<List<CourseEntity>>
    val sessions: Flow<List<SessionEntity>>

    /** 全量保存（新建或更新）：写入课程并整体替换其所有时段，单事务 */
    suspend fun saveCourse(course: CourseEntity, sessions: List<SessionEntity>): Long

    /** 删除课程及其时段（外键 CASCADE） */
    suspend fun deleteCourse(courseId: Long)

    /** 读取单个课程的完整数据，供编辑页回填 */
    suspend fun loadCourse(courseId: Long): Pair<CourseEntity?, List<SessionEntity>>

    /** 批量导入，单事务；返回成功写入的课程数 */
    suspend fun importCourses(items: List<ImportedCourse>): Int

    /** 供提醒模块使用：读取当前全部时段（挂起，一次性） */
    suspend fun allSessions(): List<SessionEntity>

    companion object {
        /** 进程内单例 */
        fun get(context: Context): CourseRepository
    }
}
```

### 2.6 冲突检测

包：`com.xixi.timetable.data.conflict`，文件名 `ConflictDetector.kt`

```kotlin
data class Conflict(
    val a: SessionEntity,
    val b: SessionEntity,
    val reason: String
)

object ConflictDetector {
    /**
     * 判定标准：dayOfWeek 相同 && 节次区间重叠 && 周次掩码相交。
     * 区间重叠：a.startSection <= b.endSection && a.endSection >= b.startSection
     */
    fun findConflicts(sessions: List<SessionEntity>): List<Conflict>
}
```

### 2.7 导入

包：`com.xixi.timetable.data.importer`

```kotlin
data class ImportedCourse(
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weekPattern: Long
)

data class ImportResult(
    val items: List<ImportedCourse>,
    val errors: List<String>
)

object CsvImporter {
    /** 表头顺序：课程名,教师,地点,星期,起始节次,结束节次,周次 */
    fun parse(text: String): ImportResult
}

object TextImporter {
    /** 解析粘贴的课表纯文本；无法识别的行写入 errors */
    fun parse(text: String): ImportResult
}

object IcsImporter {
    /** 解析 .ics 日历文本，按 VEVENT 提取课程 */
    fun parse(text: String): ImportResult
}
```

---

## 3. 提醒与通知层（remind 负责实现）

包：`com.xixi.timetable.notify`

### 3.1 调度

文件名 `ReminderScheduler.kt`

```kotlin
object ReminderScheduler {
    /** 清空并重排全部提醒。开机、打开 App、数据变更后都应调用 */
    fun rescheduleAll(context: Context)

    /** 取消全部提醒（清空课表时） */
    fun cancelAll(context: Context)

    companion object {
        /** 提前量（分钟），默认 60 */
        const val LEAD_MINUTES: Int = 60
    }
}
```

**硬性设计约束**：
- `rescheduleAll` / `cancelAll` 为**同步方法**，但其内部必须自行切到后台线程执行（会读写数据库），**不得阻塞调用方线程**——`KobiApp.onCreate()` 会在主线程直接调用它；
- 用 `AlarmManager.setExactAndAllowWhileIdle`；
- 落在**同一触发时刻**的多条提醒必须合并为**同一个闹钟**，触发后各自发通知（应对派发频率节流）；
- 关键课程（当天第一节）允许改用 `setAlarmClock`；
- 无精确闹钟权限时不崩溃，降级为 `setAndAllowWhileIdle` 并在设置页提示。

### 3.2 广播接收器

文件 `ReminderReceiver.kt`、`BootReceiver.kt`

```kotlin
class ReminderReceiver : BroadcastReceiver()   // 接收闹钟，发出通知
class BootReceiver : BroadcastReceiver()       // 接收 BOOT_COMPLETED，重排
```

两者均须在 Manifest 中以 `android:exported="false"`（BootReceiver 为 `true`）声明——由 Lead 负责写入 Manifest，remind 只需保证类名与包名一致。

### 3.3 通知

文件 `NotificationChannels.kt`、`Notifier.kt`

```kotlin
object NotificationChannels {
    const val CHANNEL_REMINDER: String = "reminder"      // 上课提醒，高重要性
    const val CHANNEL_ONGOING: String = "ongoing"        // 上课进行中，默认重要性
    fun ensure(context: Context)
}

object Notifier {
    /** 课前提醒：动态倒计时样式 */
    fun showCountdownReminder(
        context: Context,
        courseName: String,
        location: String,
        startAtMillis: Long
    )

    /** 上课进行中：分段进度样式 */
    fun showProgressOngoing(
        context: Context,
        courseName: String,
        location: String,
        currentIndex: Int,
        totalCount: Int
    )

    /** 课表总览：自定义布局样式 */
    fun showTimetableOverview(context: Context)

    /** 取消某条通知 */
    fun cancel(context: Context, notificationId: Int)

    companion object {
        /** 发送前的自检：通知权限未授予时静默跳过 */
        fun canPost(context: Context): Boolean
    }
}
```

**通知实现要求**：
- 每条通知**最多 3 个操作按钮**（Android 硬限制），且不重复点击通知本身的行为；
- 渠道重要性**只在创建时设定**（创建后不可改）；
- 震动在渠道上设置，不要用已废弃的 `setVibrate`；
- 倒计时使用 `setUsesChronometer(true)` + `setChronometerCountDown(true)` + `setWhen(startAt)`，**不要**自行每秒刷新通知。

### 3.4 权限自检

文件 `PermissionAuditor.kt`

```kotlin
data class PermissionState(
    val notificationsEnabled: Boolean,
    val exactAlarmAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val canUseFullScreenIntent: Boolean
)

object PermissionAuditor {
    fun audit(context: Context): PermissionState

    /** 打开系统通知设置页 */
    fun openNotificationSettings(context: Context)

    /** 打开「闹钟和提醒」特殊权限页，失败则回退到应用详情页 */
    fun openExactAlarmSettings(context: Context)

    /** 打开电池优化白名单页 */
    fun openBatteryOptimizationSettings(context: Context)

    /** 打开应用详情页（自启动引导用，小米自启动无法程序化检测） */
    fun openAppDetailsSettings(context: Context)
}
```

设置页（ui 负责）通过 `PermissionAuditor.audit()` 显示状态，并通过上面四个方法跳转。

---

## 4. 界面层（ui 负责实现）

包：`com.xixi.timetable.ui`

### 4.1 主题

`ui/theme/Theme.kt`：

```kotlin
@Composable
fun KobiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)

/** 课程配色表：按 colorIndex 取色，至少 10 种 */
object CoursePalette {
    val colors: List<Color>
    fun colorOf(index: Int): Color
}
```

### 4.2 导航与入口

`ui/KobiNavHost.kt`：

```kotlin
object Routes {
    const val GRID: String = "grid"
    const val LIST: String = "list"
    const val EDIT: String = "edit"                 // 新建
    const val EDIT_WITH_ID: String = "edit/{courseId}"  // 编辑
    fun editOf(courseId: Long): String = "edit/$courseId"
    const val SETTINGS: String = "settings"
    const val IMPORT: String = "import"
}

@Composable
fun KobiNavHost(navController: NavHostController, modifier: Modifier = Modifier)
```

**MainActivity 只调用 `KobiNavHost`**，其余由 ui 自行组织。

### 4.3 必需的四个屏幕

| 文件 | 职责 |
|---|---|
| `ui/grid/WeekGridScreen.kt` | 周课表网格（时间 × 星期），表头与内容滚动同步、连堂课程跨节次合并、同格多课叠放 |
| `ui/list/CourseListScreen.kt` | 列表视图，按「今天 / 明天 / 本周」分组 |
| `ui/edit/CourseEditScreen.kt` | 课程新增/编辑，含多时段编辑、周次选择、冲突提示 |
| `ui/settings/SettingsScreen.kt` | 权限自检面板 + 导入入口 + 通知测试 |

### 4.4 ViewModel

`ui/MainViewModel.kt`：

```kotlin
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val courses: StateFlow<List<CourseEntity>>
    val sessions: StateFlow<List<SessionEntity>>
    val conflicts: StateFlow<List<Conflict>>

    fun saveCourse(course: CourseEntity, sessions: List<SessionEntity>, onDone: () -> Unit)
    fun deleteCourse(courseId: Long)
    fun loadCourse(courseId: Long, onLoaded: (CourseEntity?, List<SessionEntity>) -> Unit)
    fun importFromText(text: String, kind: ImportKind, onResult: (ImportResult) -> Unit)
    fun rescheduleReminders()
}

enum class ImportKind { CSV, TEXT, ICS }
```

`ImportKind` 也定义在 `ui/MainViewModel.kt` 中。

---

## 5. Lead 负责的壳文件

### 5.1 Application

`com.xixi.timetable.KobiApp`：

```kotlin
class KobiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)   // 建渠道
        ReminderScheduler.rescheduleAll(this)
    }
}
```

### 5.2 MainActivity

`com.xixi.timetable.MainActivity`：`ComponentActivity`，`setContent { KobiTheme { ... KobiNavHost(...) } }`。

### 5.3 Manifest 中声明的权限

```
POST_NOTIFICATIONS
SCHEDULE_EXACT_ALARM
RECEIVE_BOOT_COMPLETED
VIBRATE
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

---

## 6. 命名与风格约定

- 一律 Kotlin，4 空格缩进；
- Compose 可组合函数用大驼峰，`@Composable` 注解必须标注；
- 所有字符串**硬编码在 Kotlin 中或 res/values/strings.xml**（ui 可自行决定，但 res 由 Lead 拥有，故 UI 文案建议直接写在 Kotlin 里）；
- 注释用简体中文；
- **不得**出现「导航」「灵动岛」字样；地点相关功能只展示文字，不做路线跳转。

---

## 7. 契约变更流程

若实现中发现契约有误或缺失，**不要自行改动跨模块签名**，立即在共享任务中留言，由 Lead 统一裁定并更新本文件。

---

## 8. 实现细节约定（Lead 于实现阶段补充）

以下条目是在实现过程中由 Lead 裁定并冻结的细节，供后续维护与审查对照。

### 8.1 统一作息表（ICS 导入的「时间 → 节次」映射）

第 1–12 节起始时间：

```
08:00 / 08:55 / 10:00 / 10:55 / 14:00 / 14:55 / 16:00 / 16:55 / 19:00 / 19:55 / 20:50 / 21:45
```

每节按 55 分钟估算；钟点时间匹配节次的容差为 30 分钟；`DTSTART` 视为落在第 1 教学周。

### 8.2 CSV 表头顺序

```
课程名,教师,地点,星期,起始节次,结束节次,周次
```

### 8.3 粘贴文本（TextImporter）格式

逗号 / 制表符 / 空格混排均可；行内识别顺序为「先抽周次 → 再抽节次 → 再抽星期」，剩余字段按「课程名,教师,地点」解释；**缺周次时按 1–30 周全周兜底**。

### 8.4 依赖坐标裁定

使用 `androidx.room:room-runtime:2.7.1` + `androidx.room:room-compiler:2.7.1`(KSP)。

`room-ktx` 自 Room 2.7 起已并入 room-runtime（官方称其变为空 artifact）。本项目**保留 room-ktx 坐标**：保留仅多一项可解析的依赖，移除则存在 `withTransaction` 不在 runtime 而导致编译失败的风险。在无法本地编译的前提下取保守一侧。

### 8.5 冲突判定范围

严格按 2.6 节三条判定执行：**同一门课的多个时段之间重叠也会被报为冲突**。当前产品侧接受该行为（连堂应录为单条时段），如需忽略同课程内部重叠，须另行明确。

### 8.6 学期起始日（周次校准的唯一依据）★

`weekPattern` 的 bit0 是「第 1 周」，但第 1 周对应**哪个真实日期**必须由用户指定，否则周次无法映射到日期、提醒也就排不出来。

**三方共用约定**：

```
SharedPreferences 文件名：kobi_settings
key：term_start_millis
类型：Long，值为「第 1 教学周周一 00:00」的毫秒时间戳
```

- **ui 职责**：设置页**必须**提供「学期开始日期」选择器并写入该 key（这是提醒能否工作的前提）；
- **notify 职责**：读取该 key 计算每次上课的真实时刻；未配置时回退为「本周周一」并照常排程（保证不会完全不响）；
- 读取失败或值为 0 时一律走回退，不得抛异常。

### 8.7 节次与时钟时间对照表（唯一作息表）★

`SessionEntity` 只有节次，要算出「课前 60 分钟」必须有一张节次→时钟时间的表。**data 的 ICS 导入与 notify 的排程必须共用同一张表**：

| 节次 | 起始时间 | 节次 | 起始时间 |
|---|---|---|---|
| 1 | 08:00 | 7 | 16:00 |
| 2 | 08:55 | 8 | 16:55 |
| 3 | 10:00 | 9 | 19:00 |
| 4 | 10:55 | 10 | 19:55 |
| 5 | 14:00 | 11 | 20:50 |
| 6 | 14:55 | 12 | **21:45** |

- 每节按 45 分钟上课 + 10 分钟课间估算，即相邻节次起始时间间隔 **55 分钟**；
- 超过 12 节按 55 分钟递推；
- ICS 导入方向相反（有钟点时间、需求节次），匹配容差 30 分钟；
- 第 12 节以 **21:45** 为准（由 20:50 + 55 递推得到；data 侧曾写作 21:40，属笔误）。

### 8.8 进行中通知的进度基数

`Notifier.showProgressOngoing` 的 `currentIndex` 为 **0 基**（第 1 节传 0）。内部用 `setProgress(total, currentIndex, false)`，文案显示 `currentIndex + 1`。

### 8.9 全屏 Intent

本项目**不使用**全屏 Intent。`PermissionState.canUseFullScreenIntent` 仅作状态展示（API < 34 恒为 false），Manifest 不申请 `USE_FULL_SCREEN_INTENT`（Android 14 起该权限只授予通话与闹钟类应用，且小米对「后台弹出页面」默认拒绝）。

### 8.10 通知自定义布局的资源约定

`app/src/main/res/layout/notification_timetable.xml` 由 Lead 提供，供总览通知使用（`DecoratedCustomViewStyle` + `setCustomContentView` / `setCustomBigContentView`）。可填充的 id：

```
nt_title, nt_subtitle, nt_day1, nt_day2, nt_day3, nt_day4, nt_day5
```

约束：RemoteViews 仅支持 LinearLayout / FrameLayout / RelativeLayout / GridLayout 与有限控件，**不得**在该布局中引入 ConstraintLayout / include / merge。

通知栏小图标统一使用 `@drawable/ic_notification`（单色矢量，避免彩色自适应图标在通知栏变成白块）。

### 8.11 协程依赖

协程已在 `app/build.gradle.kts` 中显式声明为 `kotlinx-coroutines-android:1.9.0`，data 与 notify 可直接使用 `kotlinx.coroutines`，无需依赖传递依赖。

### 8.12 R 资源引用禁止使用 `const val` ★

AGP 8 起 R 字段默认**不是编译期常量**（`nonFinalResIds`），因此：

```kotlin
private const val ICON = R.drawable.ic_notification   // ❌ 直接编译失败
private val ICON = R.drawable.ic_notification          // ✅ 正确写法
```

所有模块引用 R 资源（drawable / layout / string / style / id）时一律使用 `val`，**不得使用 `const val`**。

### 8.13 新建课程时时段的 courseId 回填 ★

UI 新建课程时 `course.id = 0L`，无法预知主键，因此会把时段的 `courseId` 也传 0。

`CourseRepository.saveCourse` **必须**在插入课程后用真实主键回填：

```kotlin
sessions.map { it.copy(id = 0L, courseId = courseId) }
```

UI 依赖此行为，实现方不得更改。

### 8.14 配色容量不一致（已知，暂不修）

`CoursePalette` 提供 12 色，而 `CourseRepository.PALETTE_SIZE = 10`，故批量导入的课程只会用到前 10 个颜色。属可接受的冗余；若日后调整需两边同步。

### 8.15 权限状态字段与跳转方法的对应

`PermissionState` 有 5 个字段，但只有 4 个跳转方法：`backgroundRestricted` 与 `canUseFullScreenIntent` 复用 `openAppDetailsSettings`。

### 8.16 通知布局的夜间模式

`notification_timetable.xml` 的文字颜色通过 `@color/kobi_notif_title` / `@color/kobi_notif_subtitle` 引用，并在 `values-night/colors.xml` 中提供浅色值。

星期格子底色**无法**跟随夜间模式——`View.setBackgroundColor` 没有 `@RemotableViewMethod`，RemoteViews 调用会抛 `ActionException`，故格子恒为浅底深字，属有意设计。
