# kobi 代码终审报告（独立审查位）

> 审查人：teammate `verifier`｜任务：task-8｜审查日期：2026-10-14
> 审查方式：**纯静态审查**（本机无 JDK / Android SDK / Gradle，`pwsh` 工具在本会话故障不可用，仅用 read / grep / glob）。判定口径统一为「是否会被 kotlinc / KSP / AAPT2 / lintVitalRelease 拒绝」。
> 审查范围：**29 个 Kotlin 文件全量逐行通读** —— `data/**`（12 个：实体 2 + 周次 1 + DAO/DB/仓库 3 + 冲突 1 + 导入 5）、`ui/**`（9 个）、`notify/**`（6 个）、`KobiApp.kt`、`MainActivity.kt`；另审 `AndroidManifest.xml`、`res/**`（13 个）、`app/build.gradle.kts`、`gradle/libs.versions.toml`、根构建脚本、`.github/workflows/build-apk.yml`、`.gitignore`、`gradle.properties`。
> **本报告不修改任何源码**，仅指出问题与建议改法。

---

## 结论摘要

| 类别 | 数量 |
|---|---|
| ① 阻断级（会导致 CI 编译失败） | **0** |
| ② 高危（能编译，但行为错误 / 契约明文违反） | **6** |
| ③ 中低风险与建议 | 12 |

**总体判断：可以提交 CI。** 逐项核对未发现缺失 import、未发现引用不存在的符号、未发现签名不一致、未发现 R 资源悬空引用、未发现 `const val = R.xxx` 违规、未发现契约外的第三方依赖。② 类问题不会阻断编译，但其中 H1 / H2 / H3 会直接影响「课前提醒」与「列表正确性」这两个核心体验，建议本轮一并修掉再送 CI（改这些文件不增加编译风险）。

---

## ① 阻断级问题（编译失败）

### 未发现

以下是被重点排查且**确认无问题**的项（逐条给证据，因为「无问题」也需要被信任）：

| 排查项 | 结果 | 证据 |
|---|---|---|
| 缺失 / 错误 import | 未发现 | 29 个 Kotlin 文件全部逐行读过，import 与使用点一一对应；无「用了但没 import」的符号 |
| 未使用 import（仅警告） | 未发现 | 每处 import 都在文件内被使用 |
| 引用不存在的符号 / 拼写 | 未发现 | 所有跨包符号（`WeekPatterns`、`ConflictDetector`、`CourseRepository`、`PermissionAuditor`、`Notifier`、`ReminderScheduler`、`ImportResult`、`ImportedCourse`）均存在且**公开可见** |
| `internal` / `private` 导致的跨包不可见 | 未发现 | `NotifyPrefs`、`SectionTimes`（`internal`，定义于 `ReminderScheduler.kt`）被 `Notifier.kt` 使用——**同包同模块，合法**；`NotificationIds` 为 `private object`，经 grep 确认**仅** `Notifier.kt` 内使用（5 处，全在同文件） |
| 跨模块签名不一致（ui→data/notify） | 未发现 | `MainViewModel` 调 `repository.saveCourse/deleteCourse/loadCourse/importCourses`、`CsvImporter/TextImporter/IcsImporter.parse`、`ReminderScheduler.rescheduleAll`，与 data/notify 的实际签名逐一对齐 |
| Room / KSP 用法 | 未发现 | `@Database` 的 `entities` / `version` / `exportSchema=false` 齐全；`@Entity` 外键 `parentColumns=["id"]` / `childColumns=["courseId"]` 正确且**已建 `Index("courseId")`**（否则 Room 报错）；`@Insert suspend … : List<Long>`、`suspend + Flow` 均为 Room 支持形态；`withTransaction` 来自 room-ktx 且依赖已保留（`build.gradle.kts:91`） |
| Compose API 参数名（Material3，BOM 2025.01.00） | 未发现 | 逐个核对 `TopAppBar/Scaffold/NavigationBar/NavigationBarItem/Card/CardDefaults.cardColors/ElevatedCard/FilterChip/AlertDialog/DropdownMenu/DropdownMenuItem/DatePicker/DatePickerDialog/rememberDatePickerState/HorizontalDivider/CircularProgressIndicator/OutlinedTextField(singleLine,isError,minLines,supportingText)/Icon/Text/Button/OutlinedButton/TextButton/IconButton` 的命名参数，全部真实存在 |
| `ExperimentalMaterial3Api` OptIn | 未发现 | 5 个使用 M3 实验 API 的文件（`KobiNavHost`、`WeekGridScreen`、`CourseListScreen`、`CourseEditScreen`、`SettingsScreen`、`ImportScreen`）均在**首行**声明 `@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)`（位于 `package` 之前，位置合法） |
| AGP 8 的 `const val = R.xxx` 违规 | **未发现** | 全项目 grep `const\s+val\s+\w+\s*=\s*R\.` 仅命中 `CONTRACT.md` 的反例说明；实际代码用 `private val SMALL_ICON = R.drawable.ic_notification`（`Notifier.kt:241`），写法正确 |
| R 资源悬空引用 | 未发现 | 见 ④-5 资源对照表，13 个引用全部有对应文件/条目 |
| PendingIntent 可变性（Android 12+） | 未发现 | `Notifier.kt:256-257`、`ReminderScheduler.kt:312-313` 两处 flags 均为 `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`；无任何 `FLAG_MUTABLE` 缺失点 |
| Android 13+ POST_NOTIFICATIONS | 未发现 | Manifest 已声明（`AndroidManifest.xml:5`）；`MainActivity.kt:50-58` 运行时申请；`Notifier.canPost()` 发送前二次自检；`@SuppressLint("MissingPermission")` 抑制 lint（`Notifier.kt:303`） |
| NewApi / lintVitalRelease fatal | 未发现 | 所有高版本 API 调用都有 `Build.VERSION.SDK_INT` 守卫：`canScheduleExactAlarms`（S）、`isBackgroundRestricted`（P）、`canUseFullScreenIntent`（U）；`FLAG_IMMUTABLE`(23)、`setExactAndAllowWhileIdle`(23)、`setAlarmClock`(21)、`NotificationChannel`(26)、`java.time`(26) 均 ≤ minSdk 26 |

**CI 最可能失败的非代码因素（已排查，无问题）**：

| 风险点 | 结论 | 证据 |
|---|---|---|
| 签名文件是否会被 checkout | **安全** | `app/kobi.keystore` 存在，且 `.gitignore` **未**排除 `*.keystore` / `*.jks`；`build.gradle.kts:25` 的 `file("kobi.keystore")` 相对 app 模块 = `app/kobi.keystore`，路径正确 |
| gradle-wrapper.jar 是否被忽略 | **安全** | `.gitignore:4` 显式 `!gradle/wrapper/gradle-wrapper.jar`；jar 文件存在 |
| Gradle / AGP / KSP / Kotlin 版本矩阵 | **安全** | Gradle 8.9（`gradle-wrapper.properties:3`）+ AGP 8.7.3（要求 ≥8.9 ✓）+ Kotlin 2.1.0 + KSP 2.1.0-1.0.29（与 Kotlin 完全匹配）+ compileSdk 35（AGP ≥8.6 ✓） |
| `android.useAndroidX` | **安全** | `gradle.properties:10` 已设 `true`；`android.nonTransitiveRClass=true` 与「只用本项目 R」的代码一致 |
| 依赖坐标是否全部可解析 | **安全** | compose-bom 2025.01.00 / room 2.7.1(+ktx) / navigation-compose 2.8.5 / activity-compose 1.9.3 / lifecycle 2.8.7 / core-ktx 1.15.0 / coroutines-android 1.9.0，全部真实存在且相互兼容；**无契约外依赖** |
| release APK 产物名与 CI 收集脚本 | **安全** | release 已配 `signingConfig`，产物为 `app-release.apk`，与 workflow 第一分支匹配 |

---

## ② 高危问题（能编译，但行为错误 / 契约明文违反）

### H1 — 提醒排程只排「每门课的最近一次」，14 天视界名不副实

- **位置**：`notify/ReminderScheduler.kt:114-134`（`planTriggers`）、`160-190`（`nextStartAt`）、`300-307`（`HORIZON_DAYS` 注释）
- **原文**：
  - `ReminderScheduler.kt:129`：`val startAt = nextStartAt(session, termStart, zone, now) ?: continue`
  - `ReminderScheduler.kt:302-306`：`/** 排程视界：只排未来这么多天。……取 14 天而非 7 天，是为了容忍「用户一周以上没打开过应用、也没重启过手机」的场景：……14 天 ≈ 数十个闹钟 */`
- **为什么错**：`nextStartAt` 返回 `Long?`（**单个**时刻，函数内 `return startAt` 于首次命中即返回），因此 `for (session in sessions)` 每门课时段最多只产生 **1 个**桶项。「14 天视界」在实现上并不存在——实际排的是「每门课的下一节」。注释声称能容忍「一周以上没打开应用」，但若用户装完应用后再不打开、也不重启，则每门课只会响**第一次**，第二次起静默不响（`BootReceiver` 仅 BOOT_COMPLETED / MY_PACKAGE_REPLACED 触发，`MainViewModel` 仅在 App 内数据变更时重排）。
- **怎么改**（二选一）：
  1. 把 `nextStartAt` 改成 `nextStartAts(...): List<Long>`，在 `0 until HORIZON_DAYS` 循环内**收集全部**命中时刻（`if (startAt - LEAD > now) result.add(startAt)` 而非 return），`planTriggers` 内对返回值 `forEach` 入桶；这样 14 天视界才真正成立（每天约 1–6 个桶，14 天数十个闹钟，与注释一致）。
  2. 或保留单次排程，但把注释与字段语义改为「只排每门课的下一节，依赖每次打开 App 重排」，并在方案层面接受「长期不开 App 则只响一次」这一限制。

### H2 — TextImporter 不支持空格分隔，与契约 8.3 及自身 UI 提示文案矛盾

- **位置**：`data/importer/TextImporter.kt:38`（分隔符定义）、`126-131`（字段切分）；`ui/importer/ImportScreen.kt:49-51`（提示文案）
- **原文**：
  - `TextImporter.kt:38`：`private val SEPARATOR_RE = Regex("""[,，、\t]+""")` —— **不含空格**
  - `TextImporter.kt:126`：`val fields = rest.split(SEPARATOR_RE).map { it.trim() }…`
  - `ImportScreen.kt:49-51`：`"每行一门课，逗号、制表符、空格分隔均可。……示例：高等数学 周一 1-2节 1-16周 教学楼A101"`
  - `CONTRACT.md:524`（8.3）：`逗号 / 制表符 / 空格混排均可`
- **为什么错**：空格不参与切分，导致「课程名 / 教师 / 地点」三列无法区分。以 UI 给出的示例行「高等数学 周一 1-2节 1-16周 教学楼A101」实测推演：周次段、节次段、星期段被 `removeAll` 依次替换成逗号后，`rest` 形如 `高等数学 , , , 教学楼A101`，切分结果只有一个非空字段 `"高等数学 教学楼A101"` → **课程名把地点吞掉，教师与地点均为空**。用户按官方提示输入即得到错误数据，且不会进 `errors`（静默错误）。
- **怎么改**：把分隔符扩为含空格，例如 `private val SEPARATOR_RE = Regex("""[,，、\t ]+""")`；或先 `split(Regex("""[,，、\t]+"""))` 再对每个片段按空白二次切分。改后需复查 `NOISE_RE`（`TextImporter.kt:41`）不受影响（它用 `matches` 全等匹配，与切分方式无关）。

### H3 — 列表页「今天 / 明天 / 本周」未按当前教学周过滤，会显示本周并不上的课

- **位置**：`ui/list/CourseListScreen.kt:64-82`
- **原文**：
  - `:69-82`：`val sections = remember(sessions, today, tomorrow) { … listOf("今天 · …" to inOrder.filter { it.dayOfWeek == today }, …) }`
  - `:52-54` 注释：`按「今天 / 明天 / 本周」三组展示。分组依据是星期几，不依赖学期起始日期`
- **为什么错**：过滤条件只有 `dayOfWeek`，**没有** `WeekPatterns.contains(it.weekPattern, 当前教学周)`。后果：单周课会在双周照常出现在「今天」；学期已结束或尚未开始的课程同样常驻。网格页（`WeekGridScreen.kt:122-124`）做了周次过滤，两页语义不一致——用户切到列表页会看到「今天有课」，而网格页第 N 周是空的。（注释表明是有意取舍，但这属于产品语义错误，需明确确认。）
- **怎么改**：在 `CourseListScreen` 中读同一份设置并过滤，例如：
  ```kotlin
  val prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
  val week = weekIndexFromTermStart(prefs.getLong(KEY_TERM_START_MILLIS, 0L))
  val visible = sessions.filter { WeekPatterns.contains(it.weekPattern, week) }
  ```
  再对 `visible` 分组。所需符号（`SETTINGS_PREFS_NAME` / `KEY_TERM_START_MILLIS` / `weekIndexFromTermStart` / `WeekPatterns`）均已存在（`ui/common/Common.kt`、`data/week/WeekPatterns.kt`），改动不引入新依赖。

### H4 — 「上课进行中」与「课表总览」通知没有调度入口，功能实际不可达

- **位置**：`notify/Notifier.kt:110-148`（`showProgressOngoing`）、`:160-222`（`showTimetableOverview`）；全项目调用点仅 `ui/settings/SettingsScreen.kt:265-285`（两个测试按钮）
- **原文**：`Notifier.kt:320-329`（契约要求实现）vs 调用点 grep 结果——除设置页测试按钮外**无任何生产调用**
- **为什么错**：`ReminderScheduler` 只为「课前 60 分钟」排闹钟（`ReminderScheduler.kt:130`），接收端 `ReminderReceiver.handleRemind` 只调 `showCountdownReminder`（`ReminderReceiver.kt:51-65`）。契约 3.3 定义的第三种通知形态（分段进度）与总览通知在真实使用路径上永远不会出现。这是**功能缺失**而非崩溃。
- **怎么改**：在 `ReminderScheduler.planTriggers` 中为每个时段额外排一个「上课开始时刻」的桶（同一闹钟机制，`TriggerGroup` 增加类型标记或另用一个 action），由 `ReminderReceiver` 分派到 `showProgressOngoing`；总览通知可挂在「每天首次重排」或设置页入口上。若产品决定不做，应在契约 8 节记录该裁定，避免后续维护误判为漏实现。

### H5 — 作息表被复制成两份，违反契约 8.7「必须共用同一张表」

- **位置**：`data/importer/IcsImporter.kt:28-32`（`SECTION_START_TIMES`）与 `notify/ReminderScheduler.kt:342-355`（`SectionTimes.STARTS`）
- **原文**：`CONTRACT.md:554`（8.7）：`**data 的 ICS 导入与 notify 的排程必须共用同一张表**`
- **为什么错**：两处硬编码的 12 个 `LocalTime` 目前**取值一致**（含第 12 节 21:45 ✓，符合 8.7 的更正），所以**当前行为正确**；但这违反契约「共用」的明文要求，且 `SECTION_SPAN_MINUTES`(IcsImporter) / `SECTION_INTERVAL_MINUTES`(ReminderScheduler) 也是两份 55 分钟常量，日后任一侧修改即产生「导入映射到的节次 ≠ 排程算出的时刻」的静默错位。
- **怎么改**：把 `SectionTimes` 移到 `com.xixi.timetable.data.week`（或 `data`）下作为唯一来源，`IcsImporter` 与 `ReminderScheduler` 均引用它；`ReminderScheduler.kt:340` 现为 `internal object`，跨包可继续用 `internal`（同模块可见）。

### H6 — `MainActivity.kt` 注释出现禁用词「导航」

- **位置**：`app/src/main/java/com/xixi/timetable/MainActivity.kt:23`
- **原文**：` * 2. 挂载 Compose 界面，导航交给 KobiNavHost。`
- **为什么错**：`CONTRACT.md:492`（§6）明文规定「**不得**出现「导航」「灵动岛」字样」。全项目 grep 结果：源码中该词**仅此 1 处**（其余命中均在 `CONTRACT.md` 自身）；「灵动岛」在源码中 0 处。
- **怎么改**：改为「挂载 Compose 界面，页面装配交给 KobiNavHost。」——纯注释改动，零编译风险。

---

## ③ 中低风险与建议

### M1 — 同一个 `ScrollState` 被挂到两个滚动容器（表头与内容同步）

- **位置**：`ui/grid/WeekGridScreen.kt:263-264`（两个 `rememberScrollState`）、`:296`（表头 `horizontalScroll`）、`:327`（节次列 `verticalScroll`）、`:349-350`（内容区两者）
- **为什么算风险**：实现依赖「表头内容宽度 == 内容区宽度（均为 `dayColumnWidth * 7`）」「节次列高度 == 内容区高度（均为 `sectionHeight * 12`）」这一巧合——目前成立，所以表现为正确同步。一旦日后给表头增列、或节次行高与内容行高不再一致，`ScrollState.maxValue` 两侧不同即表现为滚动范围不一致/抖动。
- **建议**：保留现状可接受；更稳的写法是表头/节次列用 `Modifier.offset { IntOffset(-state.value, 0) }` 跟随内容区滚动，或把表头放进同一滚动容器。

### M2 — 批量导入「每条记录新建一门课程」，同名课程会重复

- **位置**：`data/CourseRepository.kt:51-81`；已在 `ui/importer/ImportScreen.kt:166-171` 明示
- **说明**：一门课的多个时段（如周一 1-2 节 + 周三 3-4 节）在 CSV/文本/ICS 里是两行，导入后变成两门同名课程。UI 已如实提示，属产品取舍。
- **建议**：若希望按课程名合并，需在 `importCourses` 内先按 `name` 查询已有课程再追加时段；契约 2.5 未规定，改前应先改契约。

### M3 — `PALETTE_SIZE = 10` 与 `CoursePalette.colors` 的 12 色不一致

- **位置**：`data/CourseRepository.kt:95`（`private const val PALETTE_SIZE: Int = 10`，注释称「与 ui 层 CoursePalette 的调色板容量一致」）vs `ui/theme/Theme.kt:92-105`（12 色）
- **影响**：导入的课程只会用到前 10 种配色（行为无错，注释不准确）。**建议**：把常量改为 12 或把注释改为「至少 10 种（契约下限）」。

### M4 — `IcsImporter` 的 UTC / 全天事件处理

- **位置**：`data/importer/IcsImporter.kt:192-208`
- **说明**：`parseLocalDateTime` 直接 `removeSuffix("Z")` 后按**本地时间**解释（契约 8.1 未要求时区换算），对 `DTSTART;VALUE=DATE:20260901`（全天事件，8 字符）会走 `date.atStartOfDay()` → 00:00 → `sectionOfTime` 与任何节次差 > 30 分钟 → 记入 errors 并跳过。行为是「安全失败」，不是崩溃。**建议**：在文档/提示中说明「全天事件（VALUE=DATE）不支持」，或对全天事件给出明确错误文案。

### M5 — SharedPreferences 丢失时旧闹钟会成为「孤儿」

- **位置**：`notify/ReminderScheduler.kt:80-85`（`cancelAllNow` 依赖 `NotifyPrefs.readScheduledTriggers`）
- **说明**：用户「清除数据」或 SP 写入失败后，`scheduled_trigger_times` 为空 → 已注册的系统闹钟无法被枚举取消，触发时仍会经 `ReminderReceiver` 弹出通知（PendingIntent 里已打包课程名/时间）。**建议**：改用 `AlarmManager.cancel` 之外的兜底（例如按固定 requestCode 区间遍历取消，或把排程记录同时写入数据库）；至少在文档中记为已知限制。

### M6 — 「稍后提醒」排出的闹钟会被下一次 `rescheduleAll` 清掉

- **位置**：`notify/ReminderScheduler.kt:88-105`（`scheduleSnoozeNow` 把临时闹钟也写入 `scheduled_trigger_times`）与 `:74-77`（`rescheduleAllNow` 先全量取消再重排）
- **影响**：用户点「稍后提醒」后，若 App 恰好启动（`KobiApp.onCreate` → `rescheduleAll`）或被打开触发重排，10 分钟后的稍后提醒会被一并取消且不重建。**建议**：把临时闹钟与常规闹钟分开记录，或 `rescheduleAllNow` 只取消常规桶对应的时刻。

### M7 — 通知 id 由字符串哈希派生，存在极低概率碰撞

- **位置**：`notify/Notifier.kt:431-436`（`identity(...).hashCode() and 0x7FFFFFFF` 后 `% 100_000`）
- **影响**：不同课程/时刻理论上可能落到同一 id 而互相覆盖。概率极低，可接受；若要彻底消除，可维护一个 `(identity) -> id` 的持久映射。

### M8 — 「同格多课叠放」实现为并排分泳道

- **位置**：`ui/grid/WeekGridScreen.kt:483-544`（`layoutDay`）
- **说明**：契约 4.3 用词是「同格多课叠放」，实现是连通簇内贪心分泳道后横向平分列宽（互不遮挡）。契约用词含糊，此实现体验更好。**建议**：在契约 8 节把术语统一为「泳道并排」。

### M9 — `android:dataExtractionRules` 与 `allowBackup="false"` 并存

- **位置**：`AndroidManifest.xml:17-19`
- **说明**：两者不冲突（前者 API 31+ 生效，低版本忽略；minSdk 26 下 AAPT 正常编译），lint 只会给 Warning（不阻断 `lintVitalRelease`）。`res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml` 的根元素与结构均合法（`<full-backup-content>` / `<data-extraction-rules><cloud-backup><device-transfer>`）。**无需修改**，仅记录。

### M10 — release 构建会触发 `lintVitalRelease`

- **说明**：CI 的 `assembleRelease` 会自动跑 `lintVitalRelease`（只检查 fatal 级问题）。已逐条排查高危 fatal 项：`NewApi`（全部有 SDK_INT 守卫）、`MissingPermission`（已 `@SuppressLint`）、`ScheduleExactAlarm`（Manifest 已声明 `SCHEDULE_EXACT_ALARM`）、`UnsafeIntentLaunch`（所有 PendingIntent 均为显式 Intent）。`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 可能触发 `BatteryLife`（Error 级），但 **Error 级不进入 lintVital**，故不会阻断。**结论：当前无需处理**；若日后 CI 改为跑 `lint`，需为 `BatteryLife` 加 `tools:ignore` 或在 `build.gradle.kts` 的 `lint {}` 中降级。

### M11 — 「删除课程」的双入口行为一致性

- **位置**：`ui/edit/CourseEditScreen.kt:376-398`（对话框确认后 `deleteCourse` + `onBack()`）
- **说明**：删除后调用 `viewModel.deleteCourse` 异步执行，同步立即 `onBack()`；用户可能在删除落库前看到列表仍有该课（`Flow` 更新会自愈）。可接受，仅提示。

### M12 — 修改学期起始日后，课表页当前周次不会自动跟随

- **位置**：`ui/grid/WeekGridScreen.kt:111-118`
- **原文**：`val initialWeek = remember { … }` 与 `var week by rememberSaveable { mutableStateOf(initialWeek) }`
- **说明**：`initialWeek` 是普通 `remember`（无 key），只在**首次组合**时参与 `week` 的初始化；而 `week` 是 `rememberSaveable`，Tab 切换（`KobiNavHost.kt:160-166` 的 `saveState/restoreState`）后会从保存状态恢复旧值，`initialWeek` 的新值被忽略。因此用户在设置页改完「学期开始日期」再回到课表页，看到的仍是旧周次（需手动点「上一周/下一周」或重启进程才会对齐）。**建议**：在课表页加 `LaunchedEffect(termStartMillis) { week = weekIndexFromTermStart(termStartMillis) }`，或在设置页写入后清除该 destination 的保存状态。

### M13 — 其余「未发现」项（避免被误认为遗漏）

- 无全屏 Intent 使用（契约 8.9 ✓）；Manifest 未申请 `USE_FULL_SCREEN_INTENT` ✓；`PermissionState.canUseFullScreenIntent` 在 API<34 恒 false ✓。
- 未使用 WorkManager（仅注释提及被禁止的 DataStore，`ReminderScheduler.kt:377`）✓。
- 未使用 `setVibrate`（震动只在渠道上，`NotificationChannels.kt:56-57`）✓。
- 无地点路线跳转、无地图相关 Intent（grep `路线` 命中 0 处）✓。

---

## ④ 逐项检查的证据清单

### 4.1 契约一致性（类型 / 字段 / 方法签名）

`CONTRACT.md` 第 2–5 节声明的每一个公开符号，逐一与实现对照：**全部一致，无偏离**。

| 契约条目 | 实现位置 | 结论 |
|---|---|---|
| 2.1 `CourseEntity`（表名、字段、默认值、顺序） | `data/entity/CourseEntity.kt:11-18` | 一致 |
| 2.1 `SessionEntity`（外键 CASCADE + `Index("courseId")`） | `data/entity/SessionEntity.kt:13-31` | 一致 |
| 2.2 `OddEven` 三值 + `WeekPatterns` 六个方法 + `MAX_WEEKS=30` | `data/week/WeekPatterns.kt:4-95` | 一致（另有 `internal parseText`，契约允许 internal 新增） |
| 2.3 `CourseDao` 13 个方法（含 `insertCourses: List<Long>`） | `data/CourseDao.kt:16-57` | 逐方法一致 |
| 2.4 `AppDatabase`（`entities` / `version=1` / `exportSchema=false` / `courseDao()` / `get(Context)`） | `data/AppDatabase.kt:15-48` | 一致（另有 `internal currentOrNull()`，供事务入口） |
| 2.5 `CourseRepository`（`courses`/`sessions`/6 个方法 + `get`） | `data/CourseRepository.kt:15-116` | 一致 |
| 2.6 `Conflict`（a/b/reason）+ `findConflicts` | `data/conflict/ConflictDetector.kt:7-38` | 一致（判定式 `a.start<=b.end && a.end>=b.start` ✓；契约 8.5「同课程内部重叠也报」✓ 未加排除） |
| 2.7 `ImportedCourse` / `ImportResult` / 三个 Importer 的 `parse` | `data/importer/*.kt` | 一致 |
| 3.1 `ReminderScheduler`（`rescheduleAll`/`cancelAll`/`LEAD_MINUTES=60`） | `notify/ReminderScheduler.kt:40-60, 295` | 一致 |
| 3.2 `ReminderReceiver` / `BootReceiver` 类名与包名 | `notify/ReminderReceiver.kt:19`、`notify/BootReceiver.kt:21` | 一致（Manifest 引用见 4.4） |
| 3.3 `NotificationChannels`（两个渠道常量 + `ensure`）/ `Notifier`（4 个方法 + `canPost`） | `notify/NotificationChannels.kt:21-44`、`notify/Notifier.kt:46-232, 262` | 一致 |
| 3.4 `PermissionState` 5 字段 + `PermissionAuditor` 5 方法 | `notify/PermissionAuditor.kt:19-81` | 一致 |
| 4.1 `KobiTheme(darkTheme, content)` / `CoursePalette.colors` + `colorOf` | `ui/theme/Theme.kt:73-115` | 一致（12 色 ≥ 契约要求 10 ✓） |
| 4.2 `Routes` 六个常量 + `editOf` + `KobiNavHost(navController, modifier)` | `ui/KobiNavHost.kt:38-64` | 一致 |
| 4.4 `MainViewModel`（3 个 StateFlow + 5 个方法）/ `ImportKind` | `ui/MainViewModel.kt:25-115` | 一致 |
| 5.1 `KobiApp` 三行实现 | `KobiApp.kt:16-23` | 一致 |
| 8.6 `kobi_settings` / `term_start_millis` | `notify/ReminderScheduler.kt:384-387`、`ui/common/Common.kt:10-13` | 一致（两处副本，值相同） |
| 8.7 作息表第 12 节 = 21:45 | `ReminderScheduler.kt:354`、`IcsImporter.kt:31` | 一致（均为 21:45，无误写 21:40） |
| 8.8 `currentIndex` 0 基 + `setProgress(total, currentIndex, false)` + 文案 `+1` | `Notifier.kt:120-136`、`SettingsScreen.kt:272` | 一致 |
| 8.10 布局 id 约定 `nt_title/nt_subtitle/nt_day1..5` | `res/layout/notification_timetable.xml:27,37,54-71` | 一致 |

### 4.2 跨模块引用与可见性

- `ui → data`：`MainViewModel`/五个屏幕 → `CourseRepository`、`ConflictDetector`、三个 Importer、两个实体、`WeekPatterns`、`OddEven` —— 全部为 `public`/`object`/`data class` ✓。
- `ui → notify`：`SettingsScreen` → `Notifier`、`PermissionAuditor`；`MainViewModel` → `ReminderScheduler` —— 均为 `public object` ✓。
- `notify → data`：`ReminderScheduler`/`Notifier` → `CourseRepository`、实体、`WeekPatterns` ✓。
- **`internal` 符号跨界**：`NotifyPrefs`、`SectionTimes`（`ReminderScheduler.kt` 顶层 `internal`）被同模块的 `Notifier.kt` 使用 ✓ 合法；ui 层未触碰这两个符号（ui 用自己 `ui/common/Common.kt` 的 `internal const`）✓。
- **`private` 符号越界**：`NotificationIds`（`Notifier.kt:422` 顶层 private）经 grep 确认仅在该文件内 5 处使用 ✓ 无越界。

### 4.3 编译级专项

| 检查项 | 结果 | 关键证据 |
|---|---|---|
| 可空性 | 未发现错误 | `WeekGridScreen.kt:395` `courseById[...]` 返回 `CourseEntity?` → `CourseBlock(course: CourseEntity?)` 形参也是可空 ✓；`Notifier.kt:409` `?.name ?: "未命名课程"` ✓；`newStartAt(...) ?: continue` ✓ |
| 平台类型 | 未发现错误 | 仅 `Bundle`/`SharedPreferences` 处，均用 `getLong(..., 默认值)` / `getStringArrayListExtra(...) ?: return` 兜底（`ReminderReceiver.kt:52-54`）✓ |
| Compose 调用约束 | 未发现 | 无「在非 @Composable 里调 @Composable」；`buildSubtitle`（`CourseListScreen.kt:219`）是普通函数且只做字符串拼接 ✓ |
| `remember` 用法 | 未发现 | key 选取合理（`remember(courses)`、`remember(sessions, week)`、`remember(draftSessions.toList())`）；无「在 remember 里捕获会变值却无 key」的隐患 |
| `mutableStateListOf` 下标赋值 | 合法 | `CourseEditScreen.kt:321` `draftSessions[index] = updated` ✓ |
| `by` 委托所需的 `getValue`/`setValue` | 全部已 import | 各文件顶部均已 import ✓ |
| `keySeed++`（委托 var 自增） | 合法 | `CourseEditScreen.kt:97,119,133`，已 import `setValue` ✓ |

### 4.4 Manifest 与代码对照

| Manifest 声明 | 代码实体 | 结论 |
|---|---|---|
| `android:name=".KobiApp"` | `com.xixi.timetable.KobiApp : Application` | 匹配 |
| `android:name=".MainActivity"`（`exported=true` + MAIN/LAUNCHER） | `com.xixi.timetable.MainActivity : ComponentActivity` | 匹配 |
| `android:name=".notify.ReminderReceiver"`（`exported=false`） | `com.xixi.timetable.notify.ReminderReceiver : BroadcastReceiver` | 匹配；契约 3.2 要求 false ✓ |
| `android:name=".notify.BootReceiver"`（`exported=true` + BOOT_COMPLETED / MY_PACKAGE_REPLACED） | `com.xixi.timetable.notify.BootReceiver : BroadcastReceiver` | 匹配；契约 3.2 允许 true ✓；代码另有 `QUICKBOOT_POWERON` 白名单（`BootReceiver.kt:25-28`），该 action 无法写入 filter，注释已说明 ✓ |
| 权限 5 项 | `POST_NOTIFICATIONS` / `SCHEDULE_EXACT_ALARM` / `RECEIVE_BOOT_COMPLETED` / `VIBRATE` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 与契约 5.3 完全一致；`SCHEDULE_EXACT_ALARM` 是 `setAlarmClock`/`setExactAndAllowWhileIdle` 的前置 ✓；未多声明 `USE_FULL_SCREEN_INTENT` ✓ |

### 4.5 资源引用对照（代码/Manifest → res）

| 引用 | 状态 |
|---|---|
| `@string/app_name`（Manifest ×2） | `values/strings.xml:3` ✓ |
| `@style/Theme.Kobi`（Manifest ×2） | `values/themes.xml:5` + `values-night/themes.xml:3` ✓ |
| `@color/kobi_window_background` / `..._dark` | `values/colors.xml:3-4` ✓ |
| `@drawable/ic_launcher`（icon + roundIcon） | `drawable/ic_launcher.xml` ✓ |
| `@xml/backup_rules` / `@xml/data_extraction_rules` | 两个文件均存在且结构合法 ✓ |
| `@drawable/bg_notif_day_cell`（`styles.xml:17`） | `drawable/bg_notif_day_cell.xml` ✓ |
| `@color/kobi_notif_day_text`（`styles.xml:16`） | `values` + `values-night` ✓ |
| `@color/kobi_notif_title` / `kobi_notif_subtitle`（layout:32,43） | 两套 values 均存在 ✓ |
| `R.layout.notification_timetable`（`Notifier.kt:326`） | 存在 ✓ |
| `R.id.nt_title` / `nt_subtitle` / `nt_day1..nt_day5`（`Notifier.kt:327-343`） | 布局中 id 全部存在 ✓ |
| `R.drawable.ic_notification`（`Notifier.kt:241`、`Notifier.kt:210`） | 存在（单色矢量，`android:tint="#FFFFFF"`）✓ |
| `android.R.drawable.ic_popup_sync` / `ic_menu_close_clear_cancel`（action 图标） | 系统资源，存在 ✓ |
| RemoteViews 兼容性（契约 8.10） | 布局仅用 `LinearLayout` + `TextView` + shape drawable，无 ConstraintLayout / include / merge ✓；notify 侧只用 `setTextViewText` / `setTextColor` ✓（`setTextColor` 是 `@RemotableViewMethod`，合法） |

### 4.6 设计约束落实（任务书第 6 项）

| 约束 | 结论 | 证据 |
|---|---|---|
| 同一触发时刻的多条提醒合并为**同一个闹钟** | **落实** | `ReminderScheduler.kt:126-134` 用 `LinkedHashMap<Long, MutableList<TriggerItem>>` 按 `triggerAt` 分桶；`buildAlarmPendingIntent:247-268` 把 names/locations/startAts 三数组打包；`ReminderReceiver.kt:51-64` 触发后按 `minOf(三个 size)` 逐条发通知 ✓ |
| 倒计时用系统 Chronometer | **落实** | `Notifier.kt:84-87` `setWhen(startAtMillis)` + `setShowWhen(true)` + `setUsesChronometer(true)` + `setChronometerCountDown(true)`；无任何定时刷新逻辑 ✓ |
| 每条通知按钮 ≤3 | **落实** | 倒计时通知 2 个（`:94-95`）、进行中 1 个（`:142`）、总览 1 个（`:210`）✓ |
| 渠道重要性只在创建时设定 | **落实** | `NotificationChannels.kt:47-48, 70-71` 先 `getNotificationChannel(...) != null` 即 `return`，只在不存在时 `createNotificationChannel` ✓ |
| 震动设在渠道、不用 `setVibrate` | **落实** | `NotificationChannels.kt:56-57` ✓，全项目无 `setVibrate` |
| 无精确闹钟权限时降级不崩溃 | **落实** | `ReminderScheduler.kt:200-221`：无权限 → `setAndAllowWhileIdle`；异常 → 再降一级 + 吞异常 ✓；设置页有状态行与跳转（`SettingsScreen.kt:192-197`）✓ |
| 关键课程（当天第一节）用 `setAlarmClock` | **落实** | `ReminderScheduler.kt:136-142, 205-209` ✓ |
| `rescheduleAll/cancelAll` 同步方法、内部切后台线程 | **落实** | `ReminderScheduler.kt:47-60` 用 `CoroutineScope(Dispatchers.IO).launch`，主线程调用不阻塞（`KobiApp.kt:21`）✓ |

### 4.7 禁用项（任务书第 7 项）

| 禁用项 | 结果 |
|---|---|
| 「导航」字样 | **1 处违反**：`MainActivity.kt:23`（见 H6）。其余命中均在 `CONTRACT.md` 自身 |
| 「灵动岛」字样 | **未发现**（源码 0 处） |
| 路线跳转功能 | **未发现**（grep `路线` 0 处；`location` 仅作 `Text` 展示，无 `geo:`/`Intent.ACTION_VIEW`/地图调用） |
| 契约外第三方依赖 | **未发现**（`app/build.gradle.kts:69-98` 的 18 项依赖声明全部在契约 0/8.4/8.11 允许范围内；无 WorkManager、无 DataStore、无网络库、无图片库） |

---

## 附：送 CI 前的建议动作（按性价比排序）

1. **H6 改注释**（1 行，零风险）→ 消除唯一一处契约禁用词违规。
2. **H2 改 `SEPARATOR_RE` 加空格**（1 行）→ 让功能与 UI 提示一致，避免用户按提示输入即产生脏数据。
3. **H1 改为收集 14 天内全部命中时刻**（约 10 行）→ 让「课前提醒」在长期不开 App 的场景下也可靠，与自身注释和契约意图一致。
4. **H3 列表页补周次过滤**（约 5 行）→ 消除网格页与列表页的语义矛盾。
5. **H5 把 `SectionTimes` 收敛为单一来源**（移动 + 改引用，约 15 行）→ 满足契约 8.7 明文要求。
6. H4（进行中/总览通知的调度入口）改动最大且涉及契约歧义，建议先由 Lead 裁定产品预期，再决定是否本轮实现。

以上 1–5 项都不改变任何公开签名、不新增依赖，改完可直接送 CI，不会引入新的编译风险。
