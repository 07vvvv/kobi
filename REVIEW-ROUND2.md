# kobi 第二轮修复审查报告（REVIEW-ROUND2）

> 审查人：teammate `verifier`｜任务：task-16｜审查日期：2026-10-16
> 审查对象：commit `02c752d`（第二轮修复）相对 `HEAD~1` 的全部改动
> 改动范围（`git diff --stat` 实测，22 个文件 / +1033 −155）：
> `CONTRACT.md`、`README.md`、`app/build.gradle.kts`、`gradle/libs.versions.toml`、`KobiApp.kt`、`MainActivity.kt`、
> `data/CourseDao.kt`、`data/CourseRepository.kt`、`data/importer/CsvImporter.kt`、`data/importer/TextImporter.kt`、`data/ocr/ImageTextRecognizer.kt`(新)、
> `ui/KobiNavHost.kt`、`ui/MainViewModel.kt`、`ui/common/Common.kt`、`ui/grid/WeekGridScreen.kt`、`ui/importer/ImportScreen.kt`、`ui/list/CourseListScreen.kt`、`ui/settings/SettingsScreen.kt`、`ui/theme/Theme.kt`、
> `res/values/themes.xml`、`res/values-night/themes.xml`、`res/values-night/colors.xml`
> 另复核了本轮**未改动但仍被新代码依赖**的文件：`notify/ReminderScheduler.kt`、`notify/ReminderReceiver.kt`、`notify/Notifier.kt`、`AndroidManifest.xml`（未改，确认无需改）。
> 本机 `pwsh` 已恢复（git 可用）；无 JDK / Gradle，判定口径仍为「是否会被 kotlinc / KSP / AAPT2 / lintVitalRelease 拒绝」。

---

## 结论摘要

| 档位 | 数量 |
|---|---|
| 阻断级 | **0** |
| 高风险 | **4** |
| 中低风险 | **9** |

**可以送 CI。** 上一轮 CI 失败的两个成因（`object` 内 `companion object`、R 资源用 `const val`）本轮已确认全部修复（见 §阻断级 的核对清单第 7、8 条）。

---

## ① 阻断级（会导致 CI 编译失败或功能不可用）

### 未发现

任务要求：若判定零阻断级，必须明确写出逐文件核对过哪些 API 与语法点。以下是**本轮新增/改动代码中所有"无编译器环境下最可能翻车"的点**，逐条给出结论与依据：

| # | 核对点 | 位置 | 结论与依据 |
|---|---|---|---|
| 1 | `@file:OptIn` **多注解**写法 | `CourseListScreen.kt:1-4` | 合法。`OptIn` 签名为 `OptIn(vararg markerClass: KClass<out Annotation>)`，两个全限定 `::class` 参数合法；注解位于 `package` 之前 ✓ |
| 2 | `combinedClickable` 的 **import 路径**与签名 | `CourseListScreen.kt:9, 351` | 正确。`Modifier.combinedClickable` 属 `androidx.compose.foundation`（`CombinedClickableKt`）；调用用命名参数 `onClick=` / `onLongClick=`，二者均为可选参数 ✓；`ExperimentalFoundationApi` 已在文件级 OptIn ✓ |
| 3 | `listSaver<MutableState<Set<Long>>, Long>` 的泛型推断 | `CourseListScreen.kt:75-78` | 正确。`listSaver<Original, Saveable>(save: SaverScope.(Original) -> List<Saveable>, restore: (List<Saveable>) -> Original?)`：save 返回 `List<Long>` ✓、restore 返回 `MutableState<Set<Long>>`（= Original）✓；`restore` 允许返回可空，返回非空合法 ✓ |
| 4 | `rememberSaveable(saver = ...)` 的类型匹配 | `CourseListScreen.kt:124-126` | 正确。T = `MutableState<Set<Long>>`，与 saver 的 `Saver<MutableState<Set<Long>>, Any>` 一致；`saver` 为命名参数、`init` 为尾随 lambda ✓；`by` 委托所需 `getValue`/`setValue` 均已 import ✓ |
| 5 | `PickVisualMediaRequest(ImageOnly)` **顶层工厂函数**写法 | `ImportScreen.kt:187-191` | 正确。`androidx.activity.result.PickVisualMediaRequest` 同名顶层函数存在（参数 `mediaType: VisualMediaType`，`ImageOnly` 是 `VisualMediaType` 的实现）✓；import 包名 `androidx.activity.result.PickVisualMediaRequest` 正确 ✓ |
| 6 | `rememberLauncherForActivityResult` 的 lambda 参数类型 | `ImportScreen.kt:99-101` | 正确。`PickVisualMedia` 的 `O = Uri?`，显式写 `{ picked: Uri? -> }` 与契约签名一致 ✓ |
| 7 | **`object` 内 `companion object`**（契约 8.17） | 全仓库 grep | **未发现**。全仓库仅 3 处 `companion object`，分别在 `class ReminderReceiver:121`、`abstract class AppDatabase:24`、`class CourseRepository:178` —— **均声明在 `class` 内**，合法；`object ReminderScheduler` / `object Notifier` 的成员已全部提升到 object 顶层（`ReminderScheduler.kt:411-439`、`Notifier.kt:250-268`）✓ |
| 8 | **R 资源用 `const val`**（契约 8.12） | 全仓库 grep `const\s+val\s+\w+\s*=\s*R\.` | **未发现**。唯一的 R 图标引用是 `Notifier.kt:250` 的 `private val SMALL_ICON = R.drawable.ic_notification`（`val`）✓ |
| 9 | ML Kit 链：`TextRecognition.getClient(...)` | `ImageTextRecognizer.kt:33` | 正确。签名 `getClient(TextRecognizerOptionsInterface)`；`ChineseTextRecognizerOptions.Builder().build()` 返回类型实现该接口 ✓；import `com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions` 正确 ✓ |
| 10 | `InputImage.fromFilePath(context, uri)` | `ImageTextRecognizer.kt:40` | 正确。静态方法 `fromFilePath(Context, Uri) throws IOException` 存在（由 `text-recognition-chinese` 传递引入的 `vision-common` 提供）✓；调用被包在 `withContext(Dispatchers.IO)` + `try/catch` 内 ✓ |
| 11 | `recognizer.process(image)` 与 `recognized.text` | `ImageTextRecognizer.kt:62-65` | 正确。`TextRecognizer.process(InputImage): Task<Text>`；ML Kit `Text.getText()` → Kotlin 属性 `text` ✓ |
| 12 | `addOnSuccessListener` / `addOnFailureListener`（**play-services-tasks 传递依赖**） | `ImageTextRecognizer.kt:63, 68` | **可编译**。这两个方法是 `com.google.android.gms.tasks.Task` 的成员，而 `Task` 直接出现在 ML Kit 的公开 API 签名中，因此 `text-recognition-chinese:16.0.1` 必然把 `play-services-tasks` 作为 compile 级传递依赖暴露（`vision-common` 的 POM 依赖链）✓。低风险提示见 ③-M6 |
| 13 | `suspendCancellableCoroutine` + `continuation.resume/resumeWithException` | `ImageTextRecognizer.kt:10-13, 61-72` | 正确。`kotlin.coroutines.resume` / `resumeWithException` 均已显式 import ✓；`isActive` 守卫 ✓ |
| 14 | `recognizer.close()` | `ImageTextRecognizer.kt:49` | 正确。`TextRecognizer` 实现 `Closeable`，有 `close()` ✓；包在 `runCatching` 中 ✓ |
| 15 | `window.decorView.post { ... }` 与 lambda 内 `this` | `MainActivity.kt:56-59` | 正确。`post(Runnable)` 接受 SAM 转换的 lambda（无 receiver），lambda 内 `this` 仍解析为 `MainActivity`，故 `ReminderScheduler.rescheduleAll(this)` 传的是 Context ✓ |
| 16 | `colorResource(R.color.kobi_window_background)` | `MainActivity.kt:13, 45` | 正确。`androidx.compose.ui.res.colorResource` 包名正确 ✓；`R` 与 `MainActivity` 同包（`com.xixi.timetable`），无需 import ✓ |
| 17 | `Mutex.withLock { ... return ... }` 内的**非局部返回** | `ReminderScheduler.kt:84-90` | 正确。`kotlinx.coroutines.sync.Mutex.withLock` 是 `public suspend inline fun`，lambda 内的 `return` 合法（从 `rescheduleAllNow` 返回）✓ |
| 18 | `when` 穷尽性 | `MainViewModel.kt:141-151`（4 分支，全覆盖 `ImportKind`）、`ReminderReceiver.kt:33-40`（含 `else`）、`Common.kt:21-30`（含 `else`） | 全部合法，无缺失分支 ✓ |
| 19 | Room 新 DAO 方法 | `CourseDao.kt:46-53` | 正确。`@Query("DELETE FROM courses WHERE id IN (:ids)")` + `ids: List<Long>` 是 Room 支持的集合参数展开 ✓；`deleteAllCourses()` / `deleteAllSessions()` 无参数 ✓ |
| 20 | `abiFilters += listOf(...)` | `app/build.gradle.kts:21-23` | 正确。`abiFilters` 是 `MutableSet<String>`，对 List 用 `+=` 合法 ✓ |
| 21 | AndroidManifest 与代码对应 | `AndroidManifest.xml`（本轮未改） | 无需改：未新增 Activity/Receiver/Service；`ReminderReceiver` 只增加 action 常量（不影响 manifest）；新增权限需求为零（ML Kit bundled 模型不需要 INTERNET 权限）✓ |
| 22 | `R` 资源引用完整性 | `Notifier.kt` / `MainActivity.kt` / `SettingsScreen.kt` | `R.layout.notification_timetable`、`R.id.nt_*`、`R.drawable.ic_notification`、`R.color.kobi_window_background` 全部存在 ✓；本轮仅新增 `@color` 同名覆盖，未删除任何资源 ✓ |

**跨模块签名一致性**（任务第 3 项）逐条核对：

| 调用点 | 被调方 | 结论 |
|---|---|---|
| `KobiNavHost.kt:113` `vm.deleteCourses(courseIds)` | `MainViewModel.deleteCourses(List<Long>)` → `CourseRepository.deleteCourses(List<Long>)` | 一致 ✓ |
| `KobiNavHost.kt:143` `vm.resetAll()` | `MainViewModel.resetAll(onDone = {})`（有默认参数）→ `CourseRepository.resetAll()` | 一致 ✓ |
| `KobiNavHost.kt:106-113` `CourseListScreen(..., onDeleteCourses)` | `CourseListScreen.kt:104` `onDeleteCourses: (List<Long>) -> Unit` | 一致 ✓ |
| `KobiNavHost.kt:140-144` `SettingsScreen(..., onResetAll)` | `SettingsScreen.kt:70` `onResetAll: () -> Unit = {}` | 一致 ✓ |
| `ReminderReceiver.kt:75` `Notifier.showTimetableOverviewNow(context)`（suspend 上下文） | `Notifier.kt:173` `internal suspend fun showTimetableOverviewNow(Context)` | 一致 ✓ |
| `ReminderReceiver.kt:79` `ReminderScheduler.refreshHorizonIfNeeded(context)` | `ReminderScheduler.kt:147` `internal suspend fun refreshHorizonIfNeeded(Context)` | 一致 ✓ |
| `CsvImporter.kt:52` `TextImporter.parseWeekPatternText(columns[6])` | `TextImporter.kt:107` `internal fun parseWeekPatternText(String): Long?` | 一致（同模块 internal 可见）✓ |
| `Notifier.kt:90` `ReminderScheduler.LEAD_MINUTES` | `ReminderScheduler.kt:412` `const val LEAD_MINUTES: Int = 60`（object 顶层，public） | 一致 ✓ |
| `ImportScreen` 标签 4 元素 ↔ `ImportKind` 4 值 | `kindLabels`(CSV 表格/粘贴文本/ICS 日历/图片识别) ↔ `ImportKind{CSV,TEXT,ICS,IMAGE}` | 顺序一一对应 ✓ |

---

## ② 高风险（能编译，但行为/文案错误）

### H1 — App 内「导入」提示文案仍是被推翻的旧语义，且与 README 互相矛盾

- **位置**：`ui/importer/ImportScreen.kt:255-262`（`item(key = "action-note")`）
- **原文**：`"注意：每条记录都会新建一门课程，同一门课的多个时段会变成多门同名课程；重复导入会产生重复数据，导入前建议先清理旧课表。"`
- **为什么错**：契约 §9.1 已把 `importCourses` 改为「同名同教师合并为一门课程 + 完全相同的时段去重」，`CourseRepository.kt:71-166` 也确已实现；`README.md:49` 更明确写「重复导入同一份课表不会产生重复数据」。**App 内这段提示与数据层行为、与 README 三方矛盾**，会误导用户先去做无必要的「清理旧课表」。
- **附带澄清 Lead 的已知待办**：Lead 提到「`kindHints` 第 2 条仍写着旧的『剩下的按课程名,教师,地点解释』」——**实测已更新**（`ImportScreen.kt:59-62` 已是 §9.2 启发式的新描述）。真正待改的是本条的 `action-note`。
- **建议修法**：改为「同一门课（同名同教师）的记录会自动合并为一门课程，重复的时段会自动去重；导入后请在列表里核对。」（约 2 行）

### H2 — 设置页仍写「三种来源」，与 §9.6 的四来源不符

- **位置**：`ui/settings/SettingsScreen.kt:305-306`
- **原文**：`"支持 CSV、粘贴文本与 .ics 日历三种来源，解析成功后立即写入课表，无法识别的行会原样列出。"`
- **为什么错**：§9.6 与本轮实现均为**四种**来源（新增「图片识别」）；`ImportScreen.kt:54` 的 `kindLabels` 也是四项。设置页入口文案滞后。
- **建议修法**：改为「支持 CSV、粘贴文本、.ics 日历与图片识别（离线 OCR）四种来源……」。

### H3 — README 对「提醒重排覆盖路径」的描述漏掉了最关键的滚动续期

- **位置**：`README.md:106` vs `KobiApp.kt:12-24`
- **原文**（README）：`"启动：冷启动不在数据库读取与闹钟重排上阻塞——重排推迟到首屏显示之后，并由数据变更、开机广播（BOOT_COMPLETED）、应用更新（MY_PACKAGE_REPLACED）三条路径覆盖。"`
- **为什么错**：实现共有**五条**覆盖路径，其中被 `KobiApp.kt:17-18` 自称为「**最关键**」的是**滚动自续期**（`ReminderReceiver.kt:79` → `ReminderScheduler.refreshHorizonIfNeeded`，`ReminderScheduler.kt:147-156`，`HORIZON_DAYS=7` / `ROLLING_REFRESH_DAYS=3`）。README 只说三条，会让读者（以及后续维护者）以为「长期不打开 App 就会断档」，与实现事实相反。
- **建议修法**：README 第 106 行补一句：「此外每次提醒触发都会检查排程余量并自动重排（滚动续期），因此长期不打开 App 也不会断档。」

### H4 — `importCourses` 的库内课程索引在事务外读取（读—写竞态）

- **位置**：`data/CourseRepository.kt:83-96`（索引构建）vs `:108`（`database().withTransaction {` 开始）
- **原文**：`for (course in dao.observeCourses().first()) { ... }` —— 该行位于 `withTransaction` **之前**
- **为什么错**：合并判定依据的是事务外快照。若两次导入并发（或用户快速连点「解析并导入」两次），第二次可能在第一次创建课程之前读到旧索引 → 重复建课；反之也可能漏追加时段。`MainViewModel.importFromText` 用 `viewModelScope.launch` 逐次派发，连点即可触发并发。
- **建议修法**：把索引构建与批内 `groups` 构建一并移入 `withTransaction { }` 内部（Room 事务内读到的是一致快照），或对 `importCourses` 加互斥（可复用 `ReminderScheduler` 的 Mutex 思路，或直接在 ViewModel 层串行化导入）。

---

## ③ 中低风险与建议

### M1 — 三处「当前教学周」文案口径不统一

- **位置**：`ui/grid/WeekGridScreen.kt:220`（`"今日 $todayText · 当前第 $currentWeek 周"`）vs `ui/list/CourseListScreen.kt:205`（`"今日 $todayText · 当前第 $currentWeek 教学周"`）
- **说明**：同一概念两个措辞（「周」/「教学周」），任务书第 3 项要求三处口径一致。设置页无对应文案。
- **建议**：统一为「当前第 N 教学周」。

### M2 — 夜间主题用双色源表达同一个背景色

- **位置**：`res/values-night/colors.xml:7`（`kobi_window_background = #111318`）与 `res/values-night/themes.xml:6-7`（用 `@color/kobi_window_background_dark`）
- **说明**：两者当前值一致（`values/colors.xml:4` 的 `kobi_window_background_dark = #111318`），但一致性只靠注释约束。若日后改一处会静默跳色。
- **建议**：`values-night/themes.xml` 的两处 `@color/kobi_window_background_dark` 改为 `@color/kobi_window_background`（夜间同名覆盖后即为深色），使窗口背景只有一个来源。

### M3 — 改完学期起始日后，网格页「正在查看的周」不跟随（上一轮 M12，仍未修）

- **位置**：`ui/grid/WeekGridScreen.kt:110-118`
- **说明**：`week` 是 `rememberSaveable`，Tab 切换后从保存状态恢复旧值，`currentWeek` 的新值只用于初始化一次。用户改完设置回课表页仍看到旧周次（列表页因 `currentWeek` 用普通 `remember` 重新计算，不受影响）。
- **建议**：加 `LaunchedEffect(currentWeek) { week = currentWeek }`，或在设置页保存后清除该 destination 的保存状态。

### M4 — `IcsImporter` 与 `ReminderScheduler` 的作息表仍是两份副本（上一轮 H5，仍未修）

- **位置**：`data/importer/IcsImporter.kt:28-32`（`SECTION_START_TIMES`）与 `notify/ReminderScheduler.kt:472-485`（`SectionTimes.STARTS`）
- **说明**：本轮 `git diff` 未触及 `IcsImporter.kt`，两份副本依旧存在，两处取值目前一致（含第 12 节 21:45）。契约 8.7 的「必须共用同一张表」仍未落实。
- **建议**：把 `SectionTimes` 提升为 data 层唯一来源（例如放进 `data/week`），`IcsImporter` 改为引用。

### M5 — 滚动续期只在「课前提醒」批次触发

- **位置**：`notify/ReminderReceiver.kt:79`（在 `handleRemind` 末尾），`handleStart`（`:86-102`）末尾无续期调用
- **说明**：若某段时间只命中「上课开始」批次（例如课程已在进行中、课前提醒时刻已过），续期不会发生。影响很小（下一节课的课前提醒仍会触发并续期），但成本极低。
- **建议**：`handleStart` 末尾也调用一次 `ReminderScheduler.refreshHorizonIfNeeded(context)`。

### M6 — `play-services-tasks` 依赖未显式声明（可编译，但隐含）

- **位置**：`app/build.gradle.kts:105-106`、`gradle/libs.versions.toml:33`
- **说明**：`ImageTextRecognizer` 依赖 `com.google.android.gms.tasks.Task` 的 `addOnSuccessListener` / `addOnFailureListener`，该类型由 `text-recognition-chinese` 传递引入。当前可编译；若上游把该依赖降级为 runtime-only，会立刻断。契约 §9.6 也未要求显式声明。
- **建议**：可选地在 `build.gradle.kts` 显式加 `implementation("com.google.android.gms:play-services-tasks:<版本>")`，或在 CONTRACT §9.6 记一条「Task 来自传递依赖」的说明，避免后人误删。

### M7 — 设置页日期显示自写了一套格式化

- **位置**：`ui/settings/SettingsScreen.kt:447-450`（`formatDate` → `"2026-02-23（周一）"`）
- **说明**：契约 §9.4 要求「统一格式 `M月d日 EEEE`，由 `ui/common` 提供单一函数，各界面不得各写一套」。日期显示**位置**要求（网格页/列表页）已满足，且设置页这处是「学期起始日」的另一种用途（ISO 形式更利于核对），但形式上仍是第二套实现。
- **建议**：若要保持 ISO 显示，至少在注释中说明这是有意例外；或改用 `ui/common` 的 formatter。

### M8 — 术语禁令的扫描口径需要显式豁免

- **证据**：全仓库 grep 结果 ——
  - **Kotlin 源码**：`导航` / `灵动岛` **0 处** ✓（上一轮 `MainActivity.kt:23` 已修正，新注释不再含该词）
  - **README.md**：**0 处** ✓
  - `CONTRACT.md`：2 处（`:400` §4.2 标题、`:492` 禁令定义本身）—— 属禁令的定义处
  - `REVIEW.md`（上一轮报告）：5 处（`:110, 113, 114, 286, 287`）—— 均为**引用被审查的违规原文**，属证据性质
- **说明**：若按「全仓库 Markdown 不得出现」字面执行，`REVIEW.md` 会持续命中。这不是产品文档中的术语误用。
  - **本报告**（`REVIEW-ROUND2.md`）：仅第 154 行 1 处 —— 为说明扫描结果而不得不写出这两个词。
- **建议**：在禁令条款中显式写明豁免范围（`CONTRACT.md` 的条款本身、`REVIEW*.md` 的证据引用与扫描结论），避免下一轮扫描再次误报。

### M9 — 导入页 `status` 在解析成功后不清空（可接受，仅记录）

- **位置**：`ui/importer/ImportScreen.kt:90, 116-122`
- **说明**：识别成功后 `status` 保留「已识别 N 行，请核对后再点『解析并导入』」，用户点解析并导入后该提示仍在（`result` 卡片会同时出现）。不构成错误，仅提示可读性问题。
- **建议**：解析成功后把 `status` 置为 `null`。

---

## ④ 契约 §9 逐条落实核对

| 契约条目 | 结论 | 证据 |
|---|---|---|
| **§9.1** 同名同教师合并、库内追加、时段去重、颜色轮转、返回值为「新增课程数」 | **落实** | `CourseRepository.kt:78-166`：批内 `groups` 按 `nameKey\0teacherKey` 合并 ✓；库内索引 `existingByKey`/`existingByNameNoTeacher`/`existingByName` ✓；`teacher` 空时按 name 匹配（`:117-121`）✓；时段去重 `sessionKey`（五元组：day/start/end/week/location，`:205-210`）✓；`colorIndex = created % PALETTE_SIZE`（`:131`）✓；返回 `created`（`:164`）✓；比较前 `normalizeKey`（trim + lowercase，`:203`）✓ |
| **§9.2** 归类启发式；不得让原可解析行变错误 | **落实** | `TextImporter.kt:225-266`：地点（关键词/字母数字/纯数字/含「教」+数字）+ 教师（`TEACHER_TITLE_RE` 优先，`HAN_NAME_RE` 兜底）+ 最长片段为课程名 ✓；**强特征全不命中时回退旧行为**（`:229-235`）✓；`name` 只会取自 `fields` 非空元素，故不会把可解析行变成 `null` ✓。**注**：`:263-265` 的「不含汉字」弱特征收紧属契约字面之外的**收紧**，方向正确（避免「大学英语2」被吞为地点），Lead 已知悉 —— 建议在 §9.2 补一句把该收紧写成契约 |
| **§9.3** 「1-16周(单)」「1-16周,单周」「单周 1-16周」同掩码 | **落实** | `TextImporter.kt:107-145` 一次扫描：区间正则自带 `([单双])?` 组 → 写法①命中；写法②③走 `:128-133` 吸收独立「单/双周」标记 → 三者都归约为 `fromRange(start, end, oddEven)` ✓。`stripWeekFragments`（`:212-217`）确保标记不残留到字段 ✓ |
| **§9.4** 日期格式与显示位置 | **落实** | `Common.kt:78-93` 提供唯一入口 `todayDateText`/`tomorrowDateText`（`M月d日 EEEE` + `Locale.CHINA`）✓；网格页切换条（`WeekGridScreen.kt:218-230`）✓；列表页提示行（`CourseListScreen.kt:202-213`，含未设置提示）✓；分组标题带真实日期（`:139-140`）✓ |
| **§9.5** 重置保留设置 / 批量删除按 courseId 去重 / 同一事务 | **落实** | `CourseDao.kt:46-53` 三个新方法 ✓；`CourseRepository.resetAll()` 同一事务内 deleteAllSessions+deleteAllCourses ✓、不触碰 SharedPreferences ✓；`deleteCourses` 先 `filter{>0}.distinct()` 再同一事务（`:48-57`）✓；UI 选中集合本身按 courseId（`CourseListScreen.kt:75-78, 124-126`）✓、确认文案含「将删除全部课程与时段，学期设置保留」✓（`SettingsScreen.kt:341, 371`）；重置后 `MainViewModel.resetAll` 调用 `rescheduleReminders()` ✓ |
| **§9.6** OCR 接口签名 / 不跳过用户核对 / 容错 / 不阻塞主线程 / PickVisualMedia / data/ocr 包 | **落实** | `ImageTextRecognizer.kt:23, 30` 唯一对外挂起函数 `recognize(Context, Uri): String` ✓，`Task`/`TextRecognizer` 全封在文件内 ✓；依赖坐标正确（`libs.versions.toml:33` + `build.gradle.kts:106`）✓；四来源标签顺序 ✓；OCR 结果**只回填文本框**，仍需用户点「解析并导入」（`ImportScreen.kt:118-122`）✓；失败/无文字/取消均有提示（`ImportScreen.kt:102-103, 115-117`）✓；解码在 `Dispatchers.IO`（`ImageTextRecognizer.kt:40`）✓ |
| **§9.7** 启动边界 / 未开 R8 | **落实** | `KobiApp.kt:28-31` 只建渠道，不读库不重排 ✓；`MainActivity.kt:41-59` 先 `setContent` 再 `decorView.post` 申请权限与重排 ✓；窗口背景与首屏 Surface 均取 `@color/kobi_window_background`（`themes.xml:11-12`、`MainActivity.kt:45`），`Theme.kt:27` 的 `background = 0xFFFFFBFE` 与 `values/colors.xml:3` 的 `#FFFBFE` **实测一致** ✓；冲突检测已 `flowOn(Dispatchers.Default)`（`MainViewModel.kt:66-73`）✓；ABI 限制已加、`isMinifyEnabled = false` 保持 ✓ |

**补充核对（本轮新增功能的行为正确性）**：
- 提醒现在每天产生**两类**批次（课前 60 分钟「倒计时」+ 上课时刻「进行中」），按 `action` 分流（`ReminderScheduler.kt:384-385` → `ReminderReceiver.kt:33-40`）✓；`setAlarmClock` 只用于「每天第一批课前提醒」（`:276`），避免状态栏闹钟图标堆积 ✓。
- `planTriggers` 已改为**按日展开全部命中时刻**（`:206-235`），上一轮「每门课只排最近一次」的问题**已修复** ✓；并新增 `MAX_ALARM_BATCHES = 60` 安全阀（`:258`）与滚动续期（`:147-156`）✓。
- 上一轮发现的「进行中/总览通知无调度入口」**已修复**：`handleStart`（`ReminderReceiver.kt:86-102`）+ `EXTRA_SHOW_OVERVIEW`（`ReminderScheduler.kt:354-356`）✓。
- 上一轮 H2（TextImporter 空格分隔）**已修复**：`SEPARATOR_RE` 现含空格（`TextImporter.kt:43`）✓；上一轮 H3（列表页未按教学周过滤）**已修复**（`CourseListScreen.kt:131-135`）✓；上一轮 H6（禁用词）**已修复** ✓。

---

## 附：建议修复优先级

1. **H1**（1 处文案，2 行）—— 与数据层/README 直接矛盾，最易被用户看到。
2. **H2**（0 行代码，1 行文案）—— 四种来源说成三种。
3. **H3**（README 1 句）—— 影响对可靠性的判断。
4. **H4**（约 5 行，移动索引构建进事务）—— 并发导入的正确性。
5. M1 / M2（各 1–2 行）—— 口径与单一色源，顺手可改。

以上 1–4 项均**不改变任何公开签名、不新增依赖**，改完可直接送 CI。H4 是唯一涉及数据层控制流的改动，建议改后重点复核 `importCourses` 的返回值语义未变（仍返回新建课程数）。
