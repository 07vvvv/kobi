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

### 4.2 页面跳转与入口

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

（注：该条中的 `TimetableGridParser` 已在第四轮改名为 `TimetableLayoutParser`，见 11.2。）

---

## 11. 第四轮变更契约：PDF 课表导入与图片识别下线（2026-10-17）

本轮三项变更：**删除图片识别**、**新增 PDF 课表导入**、**周课表课程块显示改进**。
以下条目为唯一裁决，实现方不得自行变更。

### 11.1 图片识别下线的决定与理由 ★

图片识别（离线 OCR + 课表网格重建）整条路线**整体删除**：不是停用、不是保留入口后置灰。

1. **用户明确要求**下线该功能。
2. 该路线在第二轮、第三轮连续两轮修复后**仍未达到稳定可用**。第三轮已把方案改为「识别结果保留坐标 + 网格重建」（见第 10 章），仍无法稳定还原课表结构。

删除范围（截至本轮，仓库内已无残留）：

| 已删除项 | 说明 |
|---|---|
| `com.xixi.timetable.data.ocr` 整个包 | 原 `ImageTextRecognizer.kt`、`OcrLine.kt`、`TimetableGridParser.kt` 三个文件 |
| 测试 `TimetableGridParserTest.kt` | 随实现一并删除 |
| ML Kit 中文文字识别依赖 | 已从 `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 移除 |
| `ndk { abiFilters }` | 其唯一理由是抵消 ML Kit 的 native 库体积，一并删除 |
| 导入页的「选择图片」入口 | 导入来源仍为四种，「图片识别」这一项被「PDF 课表」替换 |

**因此仓库内不再包含任何 `.so` 原生库，APK 中也不再内置 OCR 模型。**

### 11.2 名称迁移（阅读历史章节时的旧名对照）★

第三轮契约（第 10 章）与更早章节中出现的标识，本轮已改名或迁移，按此对照：

| 旧名（历史章节） | 现名（本轮起） | 变化 |
|---|---|---|
| `data.ocr.OcrLine` | `data.layout.PositionedText` | 泛化为通用定位文本，字段 `text / left / top / right / bottom`（Int，左上角原点、y 向下） |
| `data.ocr.TimetableGridParser` | `data.layout.TimetableLayoutParser` | 改为通用布局重建器，输入由 OCR 结果换成 `List<PositionedText>`；算法保持不变 |

算法不变的部分（原样保留）：星期表头识列、最左列识节次、按 ★ 与周次行切块、按纵向重叠定节次范围、失败降级为视觉行 / 原文。

历史章节中的旧名**保留为当时的事实**，不再回改；新代码一律使用现名。

### 11.3 PDF 课表导入的完整数据流 ★

来源枚举 `ImportKind` 共四项，顺序固定：`CSV, TEXT, ICS, PDF`；界面展示名依次为「CSV 表格」「粘贴文本」「ICS 日历」「PDF 课表」。

PDF 这一条从选文件到入库的完整链路：

| 步骤 | 承担者 | 职责与产出 |
|---|---|---|
| 1 选文件 | `ui/importer/ImportScreen.kt` | 系统文件选择器 `ActivityResultContracts.OpenDocument`，MIME 只给 `application/pdf`；**不需要存储权限**；用户取消时 uri 为 null |
| 2 读字节 | `ImportScreen` | 从 uri 读出 `ByteArray`；读不出时给出可读提示 |
| 3 解出带坐标文本项与表格线段 | `data/pdf/PdfTextExtractor.extractAllPages(bytes)` | 返回 `List<PdfPageText>`：`items` 是带坐标文本项、`segments` 是描边直线段；坐标为 PDF 用户空间（原点左下角、y 向上）；只抛 `PdfParseException` |
| 4 聚行 / 切片段 / 坐标翻转 | `data/pdf/PdfTimetableImporter.parse(bytes)` | 按基线聚行、按水平间隙切片段；把坐标翻成左上角原点（y 向下）并乘 `COORDINATE_SCALE` 取整 |
| 4b 由表格线反推格子行带 | `PdfTimetableImporter`（内部 `buildCellBands`） | 竖直线 → 列边界，水平线 → 每列各自的行边界，产出 `List<TimetableLayoutParser.CellBand>`；提不到线段时为空列表 |
| 5 网格重建 | `data/layout/TimetableLayoutParser.parse(List<PositionedText>, List<CellBand>)` | 完成「节次 × 星期」网格重建，输出规范化文本行 `Result.lines` |
| 6 回填文本框 | `ImportScreen` | **只回填文本**，不写库 |
| 7 用户核对 | 用户 | 在文本框里检查并修改 |
| 8 解析并导入 | 用户点按钮 → `TextImporter` | 按 `TEXT` 路径入库，与「粘贴文本」共用同一条入库路径 |

为什么必须走坐标而不是直接读文字：课表的「星期」在表头、「节次」在最左列，课程文字在网格内部，三者物理上不在同一行；把 PDF 文字按出现顺序拼起来逐行解析，必然得到 0 条。

**关于「表格线反向推定节次」（本轮新增的关键设计）**：

- 真实教务课表 PDF 的表格网格是用 `m` / `l` 路径加 `S` 描边画出来的；**合并单元格中间的那条横线不会被画**，因此「某一列存在哪些行边界线」直接给出了每个格子的行跨度 —— 也就是课程的节次范围。
- 为什么必须用它：一个格子只放四行元数据（课程名 / 课程号 / 学分教师 / 周次地点）。**实测真实教务课表 PDF（2026-10 抽样，横向 A4）：字号 11pt、格子内行距 14pt，四行之间三个间隔合计 42pt，整块含字身约 53pt；而节次行高只有约 30pt —— 一个格子的文字总高接近两节课。** 所以「拿块的纵向范围去套节次带」会把单节课普遍误判成连堂，而文字里不含节数信息，**表格线是节次数量的唯一精确来源**。（该组实测数字的出处见 `TimetableLayoutParser.kt` 中 `CellBand` 的 KDoc。）
- 具体做法：竖直线去重排序得到列的左右边界；用星期表头文字的水平中心把列映射到星期几（表头缺失时按列序兜底）；最左列左侧的纯数字文本项（`1`~`12`）给出各节次位置；水平线给出**每一列各自**的行边界，相邻两条边界围成一个格子行带。
- 产物是 `TimetableLayoutParser.CellBand(weekday, top, bottom, startSection, endSection)`，经 `parse(lines, cellBands)` 传入重建器。
- **拿不到线段时自动退化**为纯文字推断路径（`cellBands` 为空即走原逻辑），行为与既有版本一致。

关键 API 形状（**签名即契约**，不得擅自更改）：

```kotlin
// data/pdf/PdfTextItem.kt
data class PdfTextItem(val text: String, val x: Float, val y: Float, val fontSize: Float)
data class PdfLineSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
data class PdfPageText(
    val width: Float,
    val height: Float,
    val items: List<PdfTextItem>,
    val segments: List<PdfLineSegment> = emptyList()   // 解析器不收集线段时为空，重建退化为纯文字推断
)
class PdfParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

// data/pdf/PdfTextExtractor.kt
object PdfTextExtractor { fun extractAllPages(bytes: ByteArray): List<PdfPageText> }

// data/pdf/PdfTimetableImporter.kt
data class PdfImportResult(
    val lines: List<String>,
    val sourceLineCount: Int,
    val droppedLineCount: Int,
    val failure: String?
)
object PdfTimetableImporter { fun parse(bytes: ByteArray): PdfImportResult }

// data/layout/PositionedText.kt
data class PositionedText(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

// data/layout/TimetableLayoutParser.kt
object TimetableLayoutParser {
    enum class Mode { GRID, VISUAL_LINES, RAW }

    /** 来自 PDF 表格线的格子行带；top / bottom 为内部坐标（y 向下） */
    data class CellBand(
        val weekday: Int,
        val top: Int,
        val bottom: Int,
        val startSection: Int,
        val endSection: Int
    )

    data class Result(val lines: List<String>, val mode: Mode, val sourceLineCount: Int, val droppedLineCount: Int = 0)

    /** cellBands 为空时行为与从前完全一致（纯靠文字位置推断节次） */
    fun parse(lines: List<PositionedText>, cellBands: List<CellBand> = emptyList()): Result
}
```

自写解析器的能力范围（**零第三方依赖**，不使用 pdfbox 等）：经典 xref 表、xref 流、对象流 `ObjStm`、`FlateDecode` / `ASCIIHexDecode` / `ASCII85Decode` / `RunLengthDecode`、`Type0` + `UniGB-UCS2-H` 编码与 `ToUnicode` CMap、Form XObject、页面旋转，以及**描边直线段**（`m` / `l` 路径 + `S` 描边，用于表格线反推节次）。

### 11.4 六条硬约束（后续改动不得破坏）★

**约束 1：异常不得穿透到界面。**
`PdfTimetableImporter.parse` **绝不抛异常**：任何失败都转成 `PdfImportResult.failure` 里的一句可读中文。
`PdfTextExtractor.extractAllPages` 只抛 `PdfParseException`。
界面层不得依赖任何其它异常类型；出现新的失败形态时，新增可读文案，而不是把异常向上抛。
已落地的文案（**不得退化为静默失败**）：

- 扫描件 / 图片版：「这个 PDF 是扫描件或图片版课表：里面没有可提取的文字，无法重建课表」
- 有文字但重建不出结构：「已从 PDF 提取到 N 处文字，但没能识别出课表结构：…」
- 文件读取失败：「读取 PDF 失败：文件打不开或是空文件，请换一份再试」

**约束 2：`TimetableLayoutParser.Result.lines` 的语义保证不变。**
非空 ⟺ 其中每一行都能被 `TextImporter` 成功解析；**重建失败时必须是空列表**（此时 `mode == RAW`，界面据此回填原始文字）。
这条既是「不该回填不可解析的行」的防线，也是「回填了就必须可解析」的防线。

**约束 3：PDF 导入与其它来源同权 —— 只回填文本框，不跳过核对。**
解析结果一律写进文本框，**必须由用户点「解析并导入」才写库**；PDF 路径不得自行入库、不得自动提交、不得省掉确认步骤。

**约束 4：坐标尺度是耦合点 —— `COORDINATE_SCALE = 1.5f`。**
PDF 的 pt 坐标乘 1.5 后取整再进布局重建器，用来对齐 `TimetableLayoutParser` 里那一套按**图片像素**调出来的阈值。
依据：教务课表 PDF 的星期列宽约 106pt、节次行距约 38pt、正文字号约 11pt，乘 1.5 后约为 159 / 57 / 16.5，与样张图片的 150 / 57 / 18 基本一致。
**改这个倍数必须同步复核 `TimetableLayoutParser` 的全部坐标阈值**，否则网格识列与切块会整体失效。

**约束 5：周课表课程块必须保证课程名称与上课地点可完整阅读，且点击可查看完整详情。**
`ui/grid/WeekGridScreen.kt`：课程名与地点的字号、最大折行数**按课程块的实际宽高（并结合用户的字体缩放）动态计算**，不写死；列宽已加大以容纳多行文本。
点击课程块弹出详情面板，完整展示课程名 / 教师 / 地点 / 星期 / 节次 / 周次，并可从中进入编辑页（原「点击直接跳编辑页」的能力仍然可达，只是多了一层确认）。

**约束 6：节次判定的首选依据是表格格子行带，退化路径必须保留且不得静默误判。**
当 `cellBands` 非空时，`sectionRange` **必须优先**按格子行带定节次，判据固定为「**块顶对上边界求最近邻**」：
1. 锚点取**块的顶边**，即块内所有行的最小 `top`（`block.minOfOrNull { it.top }`）—— 课表格子里的文字从格子左上角开始往下排，四行元数据的总高常超过格子高度并向下溢出，块心与首行中心都会被溢出带偏，只有块顶稳定贴着格顶；
2. 在 `weekday` 相同的行带中取 `top` 与锚点**距离最近**的那一条（`filter { it.weekday == weekday }.minByOrNull { abs(it.top - anchor) }`）；
3. 命中即返回 `startSection` 与 `maxOf(startSection, endSection)`（保证 `endSection` 不小于 `startSection`）；没有任何同星期行带时**继续走退化路径**，不得直接返回兜底值。
`cellBands` 为空（解析器提不到描边线段）时，**必须**退化为原有的「按块纵向范围与节次带重叠、取重叠最大者」的推断路径（`sectionRows` 也为空时再退到 `1 to 1`），不得因为拿不到线段就放弃重建或返回空结果。
**不得**改用下面两种近似判据替代上述写法：
- 用**区间包含**（锚点落在 `[top, bottom)` 内）替代最近邻 —— 相邻格子行高不等时会跨带误配；
- 用**块整体中心**（或首行中心）作锚点替代块顶 —— 会被文字向下溢出带偏。
（补注：本条锚点与最近邻判据以代码现状为准，见 `TimetableLayoutParser.kt` 的 `sectionRange`。）

### 11.5 已知边界（界面必须给出明确提示，不得静默失败）★

- **只支持矢量文本 PDF**（教务系统打印 / 导出的那种，字符以文本对象形式存在）。
- 扫描件、图片版 PDF **没有可提取文字**，无法重建 → 给出 11.4 约束 1 中的专用文案，不静默失败。
- 加密 PDF 等自写解析器不支持的特性 → 给出可读错误文案（写入 `failure`），不抛异常。
- 具体支持与不支持的 PDF 特性组合，以 `PdfTextExtractor` 的实际实现为准；本轮未逐项实测的编解码组合标记为**待验证**。
- **待验证（表格线反推节次这条路径）**：它依赖解析器真的能从内容流里提取到描边直线段（即 `PdfPageText.segments` 非空）。该路径**尚未在真机 / CI 上做过端到端验证**。若某份 PDF 的网格不是用 `m` / `l` 路径加 `S` 描边画的（例如用填充矩形、位图边框或 Form XObject 内嵌），`segments` 会为空，此时只能走 11.4 约束 6 的退化路径，节次精度回落到纯文字推断的水平。

### 11.6 可验证性 ★

- `app/src/test/java/com/xixi/timetable/data/layout/TimetableLayoutParserTest.kt` 覆盖布局重建（含 `cellBands` 优先与退化两条路径）。
- `app/src/test/java/com/xixi/timetable/data/pdf/PdfTextExtractorTest.kt` 覆盖 PDF 文本项与表格线段的提取。
- `app/src/test/java/com/xixi/timetable/data/pdf/PdfTimetableImporterTest.kt` 覆盖聚行 / 切片段 / 坐标翻转。
- `app/src/test/java/com/xixi/timetable/data/pdf/PdfCompactFixtureTest.kt` 覆盖**紧凑排版**（8~10pt 小字号、21 字符长课程号、跨行课程名、同格两门课、页脚备注行）—— 即 11.7 修复的那批结构。
- `app/src/test/resources/timetable-fixture.pdf` 与 `timetable-compact-fixture.pdf` 都是**虚构**的课表 PDF fixture（不含任何真实学号与姓名），由 `tools/make-test-pdf.py` 生成，覆盖 PDF 解析端到端。
- 验收方式：CI 的 `./gradlew testDebugUnitTest` 全绿；PDF 导入路径另需**真机人工核对**（选一份教务系统导出的课表 PDF，核对回填文本与入库结果）。

### 11.7 第二轮修复：按表格列重组（2026-10-17）★

第一轮实现（11.3）是**全页聚行**：把所有文本项按基线 y 聚行、行内按 x 排序拼成文字。它在真实
教务课表 PDF 上大面积产出字符级乱码（实测 `/(2026★20计271量)08经42济000学0.05/宋`），
课程名与地点同时报废。根因是版面本身的两个事实：

1. **逐字定位**：每个字是一条独立文本项（实测 2185 项 / 页）；
2. **长字段铺满列宽**：课程号 `/(202620271)08420000.05` 有 21 个字符，8pt 字号下宽约 88pt，
   而星期列宽只有 106pt。同一视觉行上，相邻两个格子的文字因此在 x 上互相穿插 ——
   实测同一 y（305.00）、同一字号下，两个字符串的字符 **x 完全相同**（`x=424` 上同时有
   `0` 与 `0`，`x=434` 上同时有 `7` 与 `/`）。

修复五条：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `PdfTimetableImporter.toPositionedText` | **先按表格竖线切成列，再在列内聚行**（新增 `columnRanges`）。同一行里只剩同一个格子的文字，拼接自然正确；拿不到竖线时自动退化为整页处理 |
| 2 | `SAME_LINE_TOLERANCE` | 0.35 × 字号 → **0.12 × 字号**。同一行的字基线完全相同，容差只需覆盖浮点误差；而格子内相邻两行可能只差 2pt（实测 `★计量经济学` y=266 / 代码 y=264），容差一大就被并成一行 |
| 3 | `PdfTimetableImporter.dropNoiseRows` | **按列切分之前**先整行剔除页眉页脚。备注行横跨两个星期列，切开后只剩碎片（`[网` / `络课]【吴立立（4-15）】`），关键词不再完整、下游认不出来。聚行容差取 0.5pt：实测备注行（y=22）与它上面一行课程内容（y=23）只差 1pt，容差取 1 会把课程内容一起删掉 |
| 4 | `TimetableLayoutParser.isBlockTailContinuation` / `isNameContinuation` | 区分「课内字段行」与「课程名行」。含 `/` 且**以数字开头**的行（`05/卢亭薇`）是课内行；**以汉字开头**的（`★` 丢失时的 `体育 / 詹老师 /`）是课程名行，不得并进上一块。课名续行（`验` / `学` / `线教学`）的判据是**不含 `/`**、无块首符号、无周次、非强编号 |
| 5 | `TimetableLayoutParser.firstSegmentAfterPunctuation` / `repairOverflowedWeekLines` | 地点首段被换行拆开时与下一段拼接（`…,在` + `线教学` → `在线教学`）；格子装不下时，上一格的周次行会落到下一格 `★` 行下方，把**前两行内**的 `/<周次>` 行归还给上一块 |

**~~仍然存在的边界（不得声称已解决）~~ —— 该论断已被 11.9 推翻，留此存证**：原文认为「同一个格子里有多门课时，
如果 PDF 生成器把它们的文字画在**完全相同的位置**（实测周三第 7-8 节的 `★财务管理` 与 `★计量经济学`），
这些文字在几何上不可分，任何按坐标重组的实现都无法还原」。11.9 证明这是**错的**：那两门课在内容流里
是相隔数百项的**两段独立绘制**，绘制顺序就是可靠的分隔信息，问题出在「按 y 分桶」丢掉了它。

**实测一份真实教务课表 PDF（2185 个文本项、图上共 41 个课程标记）**：重建出 40 条，
按「课程名 + 教师 + 地点 + 星期 + 节次 + 周次」六项全对的严口径，**26 条完全正确**；
其余问题的分布是：叠印导致字段混合 6 条、周六多课共格被拆碎 4 条、节次判定存疑 2 条、
字段缺失 1 条、地点少一位 1 条。**41 个课程标记都没有被整门漏掉**（每一个都有对应输出行）。
（此数字是 11.7 那一轮的历史记录；11.9 之后的同一份 PDF 见 11.9 的实测。）

### 11.8 第二轮修复的附带修复（2026-10-17）★

上面那轮实测又暴露两个**与叠印无关**的字段缺陷，一并修掉：

1. **多区间周次被丢弃** —— `TextImporter.parseWeekExpression` 在归一化文本时只抹掉空格与制表符，
   **没抹换行**。而 PDF 重建出来的块文本是**多行拼接**的，课表的周次行又常常恰好被换行切开
   （`【1-4,6-16` + `周】,3301`）。于是按 `,` 切开的末段带上 `\n`、`toIntOrNull()` 失败被整段丢掉，
   周次只剩前半截（实测 `1-4,6-16周` 变成 `1-4周`）。修法是归一化时一并抹掉 `\n` / `\r`。
   实测这一条修复了 7 条周次错误。
2. **课程名与教师被读反** —— `TextImporter.classifyFields` 用「最长片段即课程名」的启发式，
   而 `体育`(2 字) 与 `詹本乐`(3 字) 这样的组合会判反（实测重建行 `体育,詹本乐,东盟足球场`
   被读成 name=詹本乐 / teacher=体育，入库课名直接错）。修法：只有「明显更长（差 ≥ 2 字）」的
   片段才优先当课程名，长度相近时取**第一个** —— PDF 网格重建输出的行顺序是已知的（课程名在最前），
   而手工文本的常见写法（`高等数学,张三,3301`）行为不变。
3. **地点尾字被换行切开** —— `东盟足球` + `场` 落在两行。修法：地点首段不含数字、且下一段是
   不超过 4 字的纯汉字时拼接（含数字的首段一律不拼，避免把课序号 `05` 并进地点）。

### 11.9 第三轮修复：按内容流绘制顺序重组（2026-10-17）★

11.7 结尾断言「同一格里的多门课若画在完全相同的位置，几何上不可分」—— **该断言被推翻**。
把真实 PDF 的内容流原样重放（`_ci/order_probe.py` 复算）得到三条硬事实：

1. **每个字一个文本对象**：整页 2185 个 `BT…ET`，每个恰好 1 个 `Tj`；
2. **绘制顺序就是阅读顺序**：同一行字符在内容流里**连续出现**、基线完全相同、x 严格递增
   （按绘制顺序切出的 189 条视觉行，与按 x 排序的结果**逐行完全一致**）；
3. **「叠印」是错觉**：两门课的对应行可以落在**同一 y**，但它们在内容流里是**相隔数百项的两段**
   连续绘制 —— 实测 `y=305` 上 `/(202620271)07410033.` 在序 868–888、`02/周毅` 在序 1338–1342，
   且同一视觉行内字符的 **x 从不回退**。

所以「按 y 分桶、再按 x 排序拼接」丢掉的正是唯一可靠的分隔信号；它才是字符级乱码的真根因，
而不是版面本身不可解。

| # | 位置 | 改动 |
|---|---|---|
| 1 | `PdfTimetableImporter.reconstructRows` | 不再 `sortedByDescending { it.y }` 聚行，改为**保持内容流顺序、y 相对上一项跳变即换行**；行内也保持绘制顺序（不再按 x 重排） |
| 2 | `PdfTimetableImporter.dropNoiseRows` | 同上：按绘制顺序聚行。按 y 排序会把**同一 y 上不同格子/不同课程的多条行并成一行**，关键词一旦命中就一起删掉 |
| 3 | `TimetableLayoutParser.buildGridRows` | 列内行序**不再 `sortedBy { it.centerY }`**：同格多课的对应行同 y，重排会把它们交错，块切分随之把两门课的字段混在一起（现象：一条课名配了另一条课的教师与周次） |
| 4 | `TimetableLayoutParser.extractTeacher` | 姓名被换行拆开（`…05/赵` + `亮/`、`…05/宋` + `满琴/`）时，**去掉换行再匹配一次** `/中文2~4字/` |
| 5 | `LOWER_BOUND_FACTOR` | 1.2 → **2.5**。真实课表最后一格的文字会**溢出到页面之外**（实测周二第 11-12 节那门被画到 y=-33），系数太小会把课序号行与周次行整片删掉，只剩一条教师与地点全空的假记录。放宽是安全的：页脚由 `isNoiseText` 关键词整行兜底，不依赖这条下边界 |
| 6 | `tools/make-test-pdf.py` | 紧凑夹具里「同一格两门课」改为**两门课的对应行落在完全相同 y**。原夹具 y 是错开的（275/264/253 与 242/231/220），钉不住这个根因 |

**第 6 条已做反证**：把改动 1 回退成 `sortedByDescending { it.y }` 后，
`PdfCompactFixtureTest.紧凑排版的课表能全部重建出来` **立刻失败**，实际结果是
`财务管理★计量经济学,宋满,3310,周三,第7-8节,2-4周` —— 两门课被粘成一条，正是真实 PDF 那类故障的复现。

**实测同一份真实 PDF（2185 项、41 个课程标记，本机用仓库源码编译后直接跑）**：重建出 **41 条**，
**每一条都有非空的课程名与上课地点**，且都与 PDF 原文一致；11.7 当时报的 6 条字段混合、4 条拆碎、
1 条地点缺失**全部消失**。41 条样本（节选）：`体育,甘宏博,东盟足球场,周一,第3-4节,7-17周`、
`计量经济学,庄志晖,3301,周一,第5-6节,1-4,6-16周`、`财务管理,宋满琴,在线教学,周三,第7-8节,6-8,10,15周`、
`大学外语A,赖书涵,在线教学,周四,第5-6节,5周`、`税法,周毅,3207,周四,第5-6节,1-3,5-16周`、
`形势与政策,谢了尖,12122,周二,第11-12节,8周`。

**独立复核（verifier，同一份 PDF）与处理**：

1. 它指出周六两条的地点值不完整（输出 `室外`，PDF 原文是 `室外` + 换行 + `1`）。**成立，已修**：
   `firstSegmentAfterPunctuation` 现在也接受**单个数字字符**作为地点续写（`室外` + `1` → 室外1），
   两个及以上数字仍不拼接（那是下一门课的课序号，如 `05`）。
2. 它指出 `[10] [11] [38]` 的「第5-8节」应为「5-6 / 7-8」。**不成立，未采纳**：实测表格线段里
   **星期二列只有 y=349/215 两条横线、星期六列只有 349/215（两列都没有 281）**，
   即第 5-8 节在 PDF 里本来就是**一个整格**（`_ci/bands_probe.py` 复算，数据在 11.9 末尾）。
   复核者自己也标注了这三条是按手工几何划分推的、可能有换算差。

**关于节次**：节次数**只能**由表格线判定（见 11.4），它是几何事实而不是猜测。曾经的补救方案 ——
「块内若落着更靠后的节次标签就把 end 补上」—— 被测试 `单节格子的课程不会被文字溢出带成连堂`
**证伪并撤回**：单节格子的元数据本身就比一节课高，那条规则会把「第3-3节」全带成连堂
（实测真实 PDF 里周四 `大学外语A` 被带成「第3-5节」）。

因此实测里出现的「第1-1节」「第2-2节」「第5-8节」这类判定**与表格线一致，不是偏差**：
周六列的横线只有 537/522/487/469/349/215，第 1 节与第 2 节确实是两个独立的格子；星期二列与
星期六列在 349–215 之间没有横线，那一段本来就是跨第 5-8 节的**一个整格**。

⚠️ **上一稿的错误**：本节初版把 `成都人文胜迹概览` 的「第11-11节」写成「真实为第 11-12 节」，
并据此声称「已知偏差是节次可能偏短」—— 那是**没有证据的推测**（独立复核也指出无法从 PDF 证实），
已删除。若教务系统在网页上把连堂显示成两个时段而导出的 PDF 只画一个格子，**PDF 就是唯一事实来源**，
应用按 PDF 判定，不做无根据的推测。

**表格线实测数据（`_ci/bands_probe.py`，来自仓库自写提取器输出的 550 条线段）**：
| 列 | 该列存在的水平网格线 y |
|---|---|
| 星期一 | 537 / 522 / 469 / 349 / 281 / 215 / 81 / 47 / 30 / 18 |
| 星期二 | 537 / 522 / 469 / **349** / **215** / 81 / 30 / 18 |
| 星期三 | 537 / 522 / 469 / 349 / 281 / 215 / 30 / 18 |
| 星期四 | 537 / 522 / 469 / 349 / 281 / 215 / 81 / 30 / 18 |
| 星期五 | 537 / 522 / 469 / 349 / 281 / 215 / 30 / 18 |
| 星期六 | 537 / 522 / 487 / 469 / **349** / **215** / 30 / 18 |
| 星期日 | 537 / 522 / 469 / 349 / 281 / 215 / 30 / 18 |

**注意**：11.7 的第 1 条（按表格竖线切列）仍然保留，但它的理由已修正 —— 它现在的作用是
**把横跨整页的星期表头拆成每列各自完整的 `星期一`…`星期日`**（整行匹配星期正则会失败），
而不是「防止相邻列文字互相穿插」。
