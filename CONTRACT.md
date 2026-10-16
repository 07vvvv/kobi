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

### 8.17 `object` 内部不得声明 `companion object` ★

Kotlin **不允许**在 standalone `object` 内部声明 `companion object`，会直接编译失败：

```
error: Modifier 'companion' is not applicable inside 'standalone object'
```

`object` 本身已是单例，成员直接声明在 object 顶层即可：

```kotlin
object Foo {
    const val BAR: Int = 1     // ✅ 正确
    // companion object { }    // ❌ 编译失败
}
```

外部引用方式不变（仍是 `Foo.BAR`）。本项目已据此修正 `ReminderScheduler` 与 `Notifier`——两者的 `companion object` 包裹已移除，成员提升至 object 顶层。

> 由 CI 首次编译发现（本机无 JDK，静态审查未能识别）。

---

## 9. 第二轮修复契约（2026-10-16）

本轮修复四个方向：导入识别精度与排课匹配、今日日期显示、数据重置与批量删除、启动优化，并新增图片识别课程。
以下条目为唯一裁决，实现方不得自行变更。

### 9.1 导入必须按「同名同教师」合并为一门课程 ★

`CourseRepository.importCourses` **不再**为每条记录新建课程。规则（比较前统一忽略大小写与首尾空白）：

1. 导入批内：`name` + `teacher` 均相同的记录，合并为**一门** `CourseEntity`，每条记录作为一个 `SessionEntity`。
2. 与库内已有课程：若库中已存在同名同教师的课程，**追加时段到该课程**，不新建课程；`teacher` 为空时仅按 `name` 匹配。
3. 完全相同的时段（同一 `dayOfWeek` + `startSection` + `endSection` + `weekPattern` + `location`）在合并时**去重**，避免重复导入产生重复时段。
4. 颜色：新建课程时按 `written % PALETTE_SIZE` 取色（沿用现状）；仅追加时段的既有课程不改色。
5. `importCourses` 返回值语义改为「**新增的课程数**」，不含仅被追加时段的既有课程。

合并后，删除一门课程即删除其全部时段，与「课程」这一用户心智一致。

### 9.2 TextImporter 的字段归类启发式 ★

剩余字段（剥离周次、节次、星期后的片段）**不得**再按位置直接解释为「课程名,教师,地点」，须按下列顺序判定，依据全部来自文本自身特征：

1. **地点**：满足任一即判为地点（取第一个命中片段）
   - 含「楼」「馆」「室」「区」「栋」「阶」「场」「中心」之一；
   - 形如「字母 + 数字」（如 `A101`、`B305`），或纯数字/以数字结尾且长度 ≤ 12；
   - 含「教」且含数字或「学楼」。
2. **教师**：剩余片段中长度为 2~4 个汉字，或形如「X老师」「X教授」，取第一个命中者。
3. **课程名**：未被判定为地点或教师的**最长**片段。
4. 其余片段忽略；最终课程名为空 → 整行记为错误。

> 该启发式只允许**提高**命中率：任何拿不准的情形一律回退为「第一个片段即课程名」，不得让原本可解析的行变成错误。

### 9.3 周次与单双周必须整体解析 ★

「1-16周(单)」「1-16周,单周」「单周 1-16周」三种写法**必须**解析为同一个周次位掩码。

现状缺陷：先匹配周次区间，紧随其后的「单/双」标记被丢弃，导致单周课被排到全周。

修复要求：周次解析改为**一次扫描**，允许同一行内「区间 + 单双标记」组合，组合语义与 `WeekPatterns.fromRange(start, end, oddEven)` 一致；未识别出任何周次时按 1~30 周全周处理（沿用现状）。

### 9.4 今日日期必须显示在周次与课表界面 ★

- 统一格式 `M月d日 EEEE`（如 `10月16日 星期四`），由 `ui/common` 提供**单一**函数，各界面不得各写一套。
- 周课表网格页：周次切换条区域显示「今日 10月16日 星期四」与当前教学周。
- 课程列表页：把「按第 N 教学周筛选」提示行升级为同时显示今日日期与当前教学周。
- 列表页的「今天 · 周一」「明天 · 周二」分组标题改为带具体日期（今天用真实日期，明天用真实日期 +1）。
- 学期起始日未设置时，日期照常显示、周次按第 1 周（沿用现状口径），并在该行提示「未设置学期开始日期」。

### 9.5 数据重置与批量删除的语义 ★

- **一键重置**：清空全部 `courses` 与 `sessions`，**保留** `kobi_settings`（学期起始日等）。执行前二次确认，确认文案须写明「将删除全部课程与时段，学期设置保留」。
- **单条删除**：列表页每条记录可删除（滑动删除或长按菜单均可），删除后立即重排提醒。
- **多选删除**：列表页可进入多选模式，勾选多条后一次删除；选中的可能是**同一门课程的多个时段**，删除时必须按 `courseId` 去重后再调用批量删除。
- 数据层契约：`CourseDao` 增加 `deleteAllCourses()` / `deleteAllSessions()` / `deleteCoursesByIds(ids)`；`CourseRepository` 增加 `resetAll()` / `deleteCourses(ids)`，两者都必须在**同一事务**内完成。
- 重置与删除成功后都必须调用 `ReminderScheduler.rescheduleAll`；重置后不得残留任何已排闹钟。

### 9.6 图片识别课程（离线 OCR）★

- 依赖固定为 `com.google.mlkit:text-recognition-chinese:16.0.1`（bundled 内置模型，不联网、不依赖 GMS）。
- 导入页来源标签固定为四个，顺序为：`CSV 表格 / 粘贴文本 / ICS 日历 / 图片识别`。`ImportKind` 枚举相应扩展。
- 交互：选图（系统图片选择器）→ 识别 → **把识别文本回填到粘贴文本框**，由用户核对后走既有文本解析流程。不得跳过用户确认直接入库。
- 容错：识别失败、图中无文字、用户取消选图，都必须给出可读提示且不崩溃；识别不得阻塞主线程。
- 选图使用 `ActivityResultContracts.PickVisualMedia`（无需存储权限）。
- OCR 代码放在 `data/ocr/` 包内，对外只暴露挂起函数；不得把 `Task` 泄漏到调用方。

### 9.7 启动优化的边界 ★

- `KobiApp.onCreate` **不得**在冷启动窗口内读数据库或重排闹钟；重排推迟到首屏可见之后（或交给出既有数据变更/开机路径）。
- `MainActivity` 必须**先** `setContent`，**再**申请通知权限，权限请求不得抢在首屏之前。
- 启动主题在 `windowBackground` 外不得引入白屏跳变；窗口背景色须与 Compose 首屏背景一致。
- 首屏（周课表）不得在组合期做冲突检测等全量重计算，应延迟到首屏可见后或按需触发。
- 允许在 `build.gradle.kts` 中限制 ABI 以减小体积；**不得**开启 R8 或资源压缩——本轮无法在真机验证混淆后的 release 运行，风险不可控。

## 十、第三轮修复契约：图片识别必须走「网格重建」

用户实测反馈：图片识别「解析成功零条，全部无法识别」；样张为**教务系统网页截图**（几行几列的网格，
每格内含课程名 / 教师 / 地点 / 周次）。

### 10.1 根因认定 ★

现状把 OCR 结果拼成纯文本、交给 `TextImporter.parse` **逐行**解析，而 `TextImporter.parseLine`
要求**同一行内**同时出现「星期」与「节次」（见其 `dayOfWeek !in 1..7 → null` 与 `startSection < 1 → null`）。

课表截图的星期在**表头**、节次在**最左列**、课程文字在**网格内部**，三者物理上不在同一行；
样张的格子内更不含任何「周X」「第N节」字样。因此每一行都必然落入 `errors`，导入结果**恒为 0 条**。

这属于**数据通道设计错误**，不是启发式命中率问题。禁止再用「调参提高行内命中率」的方式修补。

### 10.2 数据通道：OCR 必须保留坐标 ★

- `ImageTextRecognizer.recognize` 返回类型由 `String` 改为 `List<OcrLine>`。
- `OcrLine(text, left, top, right, bottom)` 为纯 Kotlin 数据类（不得依赖 `android.*`），置于 `data/ocr/`。
- 取值来源必须是 `Text.textBlocks → block.lines → line.text + line.boundingBox`；**不得**只取 `Text.text`。
- 返回前按 `top` 再 `left` 升序排序。
- 识别前必须做**放大预处理**，用 `InputImage.fromBitmap` 送入：最长边目标约 2600px
  （最长边 < 1000px 放大 2.5×；< 1800px 放大 1.6×；< 2600px 放大 1.2×；≥ 2600px 保持 1.0×）。
  理由：教务系统截图的单行字高常小于 12px，不放大时 ML Kit 的中文识别率显著劣化。
- 解码失败、识别失败、图中无文字一律返回**空列表**，绝不抛异常。

### 10.3 网格重建解析器 ★

新增 `data/ocr/TimetableGridParser`（纯 Kotlin，零 `android.*` 依赖，须可被 JVM 单元测试覆盖）。

1. **列（星期）**：匹配「周一…周日 / 星期一…星期日 / 礼拜一…礼拜日」的行视为表头，收集其 `centerX`；
   不同星期数 ≥ 3 方可认定网格模式。列归属区间 = 相邻列中心的**中点**，按 `centerX` 落区间判定。
2. **行（节次）**：位于最左列（`centerX` 小于第一列左边界）且匹配节次标签的行
   （纯数字 1~12、「第N节」、「N-N节」、`HH:MM`）构成节次列，收集其 `centerY` 作为节次行中心。
3. **切块**：同一列内按 `centerY` 升序，相邻行纵向间隙超过平均行高 1.6 倍、或该行文本以 `★`/`*` 开头时开新块；
   **一个块 = 一门课程的一个时段**。
4. **节次范围**：块的纵向覆盖范围就近匹配节次行中心；跨越多个行中心时取覆盖到的全部节次（最小~最大）。
5. **块内字段提取**（为难样张而设计，但不得硬编码某一所学校的格式）：
   - 周次：`【…】` / `[…]` / 含「周」的数字与区间组合（如 `1-4,6-16周`），复用 `WeekPatterns`；
   - 地点：`】,XXX` 之后，或末段的短串，沿用 9.2 的地点特征；
   - 教师：形如 `/中文2~4字/` 的片段，或沿用 9.2 的教师特征；
   - 课程名：`★` 后第一段，剔除 `(学分)/(课程号)` 之类的编号片段，取最长的剩余片段。
6. **降级**：
   - 无星期表头（< 3 个）→ 按 `centerY` 聚类为视觉行，行内按 `centerX` 排序、用空格连接，交 `TextImporter.parse`；
   - 可解析行数为 0 → `Mode.RAW` 且 `lines` 为空，由界面回填 OCR 原文（见 10.4）。
7. **`Result.lines` 的语义保证（硬性）**：**非空 ⟺ 其中每一行都能被 `TextImporter` 解析成功**。
   不满足该性质的行必须剔除；剔除后为空则返回 `Mode.RAW` + 空 `lines`。
   理由：界面以 `lines.isNotEmpty()` 判定「重建成功」，语义一旦被破坏，界面就会对一批注定
   解析失败的文字谎报「已重建 N 个时段」，用户点「解析并导入」才发现全是无法识别 ——
   这正是本轮要消灭的体验。
8. **降噪与统计口径（硬性）**：
   - 页眉/页脚（如 `…学年第1学期 个人课程表`、`学号… 学生姓名… 所属班级… 总学分…`、`备注 …`）
     **不得**参与网格重建：命中关键词 `课程表 / 学期 / 学号 / 姓名 / 班级 / 学分 / 备注 / 教务处 / 打印 / 导出`
     的行直接排除；且网格内容的 `centerY` 必须落在表头之下（有节次标签时还要落在最后一个节次带之内）。
   - 行高统计口径改为**网格内容行高度的中位数**，不得用含页眉在内的平均值 —— 否则大字号页眉会抬高
     切块阈值，把同一格内的两门课合并成一条而丢课。
9. **输出**：`parse(lines: List<OcrLine>): Result`，其中
   `Result(lines: List<String>, mode: Mode, sourceLineCount: Int)`、`Mode = GRID | VISUAL_LINES | RAW`。
   `lines` 为**可直接交 `TextImporter.parse` 成功解析**的文本行（GRID 为规范化文本，VISUAL_LINES 为视觉行）；
   GRID 行的格式固定为 `课程名,教师,地点,周X,第N-M节,周次`；生成时**必须**清洗字段内的
   `,`、`，`、`、`、`\t` 与空格（替换为 `·` 或删除），避免被 `SEPARATOR_RE` 二次拆分。

### 10.4 交互与提示 ★

- 识别后回填**规范化文本**（而非 OCR 原文），提示改为「已从图片重建 N 个时段，请核对后再点「解析并导入」」。
- 重建 0 条但 OCR 有文字时，提示必须说明「未能识别出课表结构」，并把 OCR 原文填入文本框；
  不得与「没有识别到文字」混淆（两者失败原因完全不同）。
- 用户核对环节不得跳过，不得由 OCR 直接写入课表。

### 10.5 可验证性 ★

- `TimetableGridParser` 必须由 **JVM 单元测试**覆盖（`app/src/test/java/`），并由 CI 执行
  `./gradlew testDebugUnitTest`；编译通过不再作为唯一验收标准。
- 测试样本必须覆盖：标准网格、块内多行、无节次标签、无星期表头（降级）、空输入、
  `★` 分格多课、跨行合并单元格。
- 测试数据**不得**包含真实学号、姓名、班级等个人信息。

### 10.6 周次正则的两条硬约束（第三轮 CI 回归修复）★

图片识别降级路径（`VISUAL_LINES`）与用户手动粘贴文本都依赖 `TextImporter.parseLine` 能从**同一行内**
同时认出星期与节次。周次正则曾允许数字与「周」之间有空格，于是空格分隔的行里
「教一101 周一」被匹配成周次片段 `01 周`：周次被误判成第 1 周、行内「周一」被剥离成「一」、
整行解析失败（用户看到「无法识别」，图片降级路径则直接退回 RAW）。

因此 `TextImporter` 的三条周次正则（`WEEK_FRAGMENT_RE`、`WEEK_RANGE_RE`、`WEEK_LIST_RE`）必须同时满足：

1. `(?<!\d)`：周次数字不得是更长数字串的**尾部**，避免从「教一101」里切出 `01`；
2. `(?!\s*[一二三四五六日天])`：周次里的「周」是**单位**，其后不得紧跟中文星期字符，
   避免单个数字地点（「A3 周一」）被误吞；
3. 「周」后紧跟**数字**（如 `1-16周1-2节`）是合法写法，故第 2 条只挡中文星期字符，不挡数字。

回归防线：`TextImporterTest.地点数字紧邻星期时不得被当成周次片段`。

4. 片段识别正则必须**单一来源**：`TimetableGridParser` 的切块信号（`splitIntoBlocks`）与地点锚点
   （`extractLocation`）直接复用 `TextImporter.WEEK_FRAGMENT_RE`（`internal`），不得再复制副本 ——
   本轮就出现过「副本漏改、两处行为漂移」。`WEEK_ODD_EVEN_RE` 同样加第 2 条断言，
   避免「单 周一」这类畸形组合破坏行内星期。
