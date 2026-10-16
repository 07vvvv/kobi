package com.xixi.timetable.data.importer

import com.xixi.timetable.data.week.OddEven
import com.xixi.timetable.data.week.WeekPatterns

/**
 * 纯文本课表导入器：解析用户粘贴的课表文本，每行一个时段。
 *
 * 支持的写法（顺序无关，可混用逗号、制表符、空格分隔）：
 * - 「高等数学,张老师,教一101,周一,1-2节,1-16周(单)」
 * - 「高等数学 周一 第1-2节 1-16周」
 * - 「大学物理 周三 3-4节 单周」
 *
 * 周次按契约 9.3 整体解析（允许「区间 + 单双标记」分离书写）；
 * 剩余字段按契约 9.2 的启发式归类为 地点 / 教师 / 课程名。
 * 行内必须能识别出星期与节次，否则整行写入 [ImportResult.errors]；解析过程绝不抛异常。
 */
object TextImporter {

    /**
     * 周次正则的两条硬约束（否则「地点 + 星期」会被当成周次，《真实故障》）：
     *
     * 1. `(?<!\d)`：周次数字不能是更长数字串的**尾部**。地点「教一101」后接「周一」时，
     *    没有它就会从 `101` 里切出 `01`，把「01 周(一)」整段当周次吃掉、并破坏行内的星期；
     * 2. `(?!\s*[一二三四五六日天])`：周次里的「周」是**单位**，后面不能紧跟中文星期字符。
     *    没有它，地点是单个数字（如「A3 周一」「3 周一」）时同样会被误吞。
     *
     * 「周」后紧跟**数字**（如 `1-16周1-2节`）是合法写法，故第二条只挡中文星期字符。
     */

    /** 周次区间，可带单双标记，如「1-16周(单)」「第2-8周」 */
    private val WEEK_RANGE_RE = Regex("""第?\s*(?<!\d)(\d{1,2})\s*[-~—～至]\s*(\d{1,2})\s*周(?!\s*[一二三四五六日天])\s*[（(]?\s*([单双])?\s*[)）]?""")

    /** 周次枚举，如「2,4,6周」「第2,4,6周」 */
    private val WEEK_LIST_RE = Regex("""第?\s*(?<!\d)(\d{1,2}(?:\s*[,，、]\s*\d{1,2})+)\s*周(?!\s*[一二三四五六日天])""")

    /** 独立的单/双周标记，如「单周」「双周」（同样不得命中「单 周一」这类畸形组合） */
    private val WEEK_ODD_EVEN_RE = Regex("""([单双])\s*周(?!\s*[一二三四五六日天])""")

    /**
     * 含「周」的**完整**周次片段：允许「- , . 至」连接的区间与 `(单)/(双)` 混合出现。
     *
     * 整段抓取才能覆盖 `1-4,6-16周`（只有末段带「周」）与 `1-3(单),6.8-9周` 这类写法；
     * 逐个 find 区间会只拿到最后一段，把前面的区间整段丢掉。
     *
     * **单一来源**：`TimetableLayoutParser` 的切块信号与地点锚点直接复用这一份。
     * 不得再复制副本 —— 复制过一次，结果上面的两条断言只修了这里、副本漏修，两处行为漂移。
     */
    internal val WEEK_FRAGMENT_RE = Regex(
        """(?<!\d)\d{1,2}(?:\s*[-~—～至,，.]\s*\d{1,2}|\s*[（(]\s*[单双]\s*[)）])*\s*[（(]?\s*[单双]?\s*[)）]?\s*周(?!\s*[一二三四五六日天])\s*[（(]?\s*[单双]?\s*[)）]?"""
    )

    /** 逗号被读成点/圆点：仅当夹在数字之间时归一为逗号（周次里没有合法小数） */
    private val DOT_BETWEEN_DIGITS_RE = Regex("""(?<=\d)[.．•·](?=\d)""")

    /** 节次区间，如「第1-2节」「3-4节」 */
    private val SECTION_RANGE_RE = Regex("""第?\s*(\d{1,2})\s*[-~—～至]\s*(\d{1,2})\s*节""")

    /** 单节次，如「第3节」 */
    private val SECTION_SINGLE_RE = Regex("""第?\s*(\d{1,2})\s*节""")

    /** 星期，如「周一」「星期三」「礼拜天」 */
    private val WEEKDAY_RE = Regex("""(?:周|星期|礼拜)\s*([一二三四五六日天1-7])""")

    /**
     * 字段分隔符：逗号、顿号、制表符与空格。
     * 契约 8.3 要求「逗号 / 制表符 / 空格混排均可」，故必须包含空格。
     * 代价：课程名内部若含空格会被一并拆开，建议用户用逗号分隔。
     */
    private val SEPARATOR_RE = Regex("""[,，、\t ]+""")

    /** 周次枚举内部的周次分隔符 */
    private val WEEK_SEPARATOR_RE = Regex("""[,，、\s]+""")

    /** 剥离「第」「周」「单」「双」等关键字后可能残留的孤立片段 */
    private val NOISE_RE = Regex("""[第周单双节]+""")

    /** 地点关键词（契约 9.2 第 1 条） */
    private val PLACE_KEYWORDS = arrayOf("楼", "馆", "室", "区", "栋", "阶", "场", "中心")

    /** 形如「字母 + 数字」的地点，如 A101、B305 */
    private val ALNUM_PLACE_RE = Regex("""^[A-Za-z]+\d+[A-Za-z]?$""")

    /** 纯数字的地点，如 101 */
    private val PURE_DIGIT_RE = Regex("""^\d+$""")

    /** 汉字字符 */
    private val HAN_CHAR_RE = Regex("""[\u4e00-\u9fa5]""")

    /** 以数字结尾 */
    private val DIGIT_TAIL_RE = Regex("""\d$""")

    /** 教师：2~4 个汉字 */
    private val HAN_NAME_RE = Regex("""^[\u4e00-\u9fa5]{2,4}$""")

    /** 教师：X老师 / X教授 */
    private val TEACHER_TITLE_RE = Regex("""^[\u4e00-\u9fa5]{1,4}(?:老师|教授)$""")

    /** 地点判定的长度上限（契约 9.2 第 1 条） */
    private const val PLACE_MAX_LENGTH: Int = 12

    /** 字段归类结果 */
    private data class FieldClassification(
        val name: String,
        val teacher: String,
        val location: String
    )

    fun parse(text: String): ImportResult {
        val items = ArrayList<ImportedCourse>()
        val errors = ArrayList<String>()
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("//")) continue
            val parsed = parseLine(line)
            if (parsed == null) {
                errors.add("第${index + 1}行：无法识别「$line」")
            } else {
                items.add(parsed)
            }
        }
        return ImportResult(items = items, errors = errors)
    }

    /**
     * 解析周次文本（契约 9.3 + 多区间合并）。
     *
     * - 先用 [WEEK_FRAGMENT_RE] 整段抓取含「周」的片段，再交 [parseWeekExpression] 逐段解析：
     *   「1-4,6-16周」得到两段并集，「1-3(单),6.8-9周」里的「(单)」只作用于 1-3；
     * - 抓不到含「周」的片段时，退一步在整个文本上再试一次（覆盖 CSV 周次列「1-16(单)」这类写法）；
     * - 仍失败则回退 [WeekPatterns.parseText]，保证既有 CSV / 文本导入口径不回归。
     */
    internal fun parseWeekPatternText(text: String): Long? {
        // 独立的「单周/双周」标记必须先算出来，并在**两次**尝试里都传下去：
        // 文本里可能完全没有含「周」的数字片段（CSV 周次列填「单周」、或行内只写「单周」），
        // 此时若把 OddEven.ALL 传进去，单双周信息就会整条丢失、退化成「1-30 周全周」。
        val standalone = standaloneOddEvenOf(text)
        val fragment = WEEK_FRAGMENT_RE.find(text)?.value
        if (fragment != null) {
            parseWeekExpression(fragment, standalone)?.let { return it }
        }
        parseWeekExpression(text, standalone)?.let { return it }
        return WeekPatterns.parseText(text)
    }

    /**
     * 取出「不含数字的纯单双标记」（如「1-16周,单周」里的「单」）作为整串默认。
     *
     * 必须先剥离**最长的周次片段**：若先跑区间正则，会把片段拆碎、只留下 `(单)` 这类残骸，
     * 于是「1-3(单),6.8-9周」被误判成整串单周，后面的 6、8 被过滤掉。
     */
    internal fun standaloneOddEvenOf(text: String): OddEven {
        var stripped = WEEK_FRAGMENT_RE.replace(text, " ")
        stripped = WEEK_RANGE_RE.replace(stripped, " ")
        stripped = WEEK_LIST_RE.replace(stripped, " ")
        // 必须要求「单/双」**紧邻「周」**（走 WEEK_ODD_EVEN_RE），不能用裸 contains：
        // 否则课程名 / 教师名 / 地点里出现的「单」「双」（单片机原理、双语教学、单伟、双语楼）
        // 会被当成整串单双周标记，把整门课的周次错误地过滤成单周或双周。
        return oddEvenOf(WEEK_ODD_EVEN_RE.find(stripped)?.groupValues?.get(1).orEmpty())
    }

    /**
     * 逐段解析周次表达式（区间 / 枚举 / 单双），文本导入与 PDF 网格重建共用。
     *
     * - 单双周**逐片段局部绑定**：`1-3(单),6.8-9周` 里的「(单)」不会把后面的 6、8 过滤掉；
     * - [defaultOddEven] 是整串默认（来自独立的「单周/双周」标记），片段自带标记优先级更高；
     * - 单个片段解析失败只跳过该片段；全串都解析不出来时返回 null。
     */
    internal fun parseWeekExpression(raw: String, defaultOddEven: OddEven): Long? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        text = text.replace('（', '(').replace('）', ')')
            .replace('，', ',').replace('、', ',')
            .replace('－', '-').replace('—', '-').replace('~', '-').replace('～', '-')
            .replace("至", "-")
            .replace("【", " ").replace("】", " ")
            .replace("[", " ").replace("]", " ")
            .replace("{", " ").replace("}", " ")
            .replace("〔", " ").replace("〕", " ")
            .replace("第", "")
            .replace("周", "")
            .replace(" ", "").replace("\t", "")
        // 逗号被识别成点/圆点：夹在数字之间时归一为逗号（周次里没有合法小数）
        text = DOT_BETWEEN_DIGITS_RE.replace(text, ",")
        if (text.isEmpty()) return null

        var mask = 0L
        var matched = false
        for (rawPart in text.split(',')) {
            if (rawPart.isEmpty()) continue
            var oddEven = defaultOddEven
            if (rawPart.contains("单")) {
                oddEven = OddEven.ODD
            } else if (rawPart.contains("双")) {
                oddEven = OddEven.EVEN
            }
            val part = rawPart.replace("单", "").replace("双", "")
                .replace("(", "").replace(")", "")
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            if (dash > 0) {
                val start = part.substring(0, dash).toIntOrNull() ?: continue
                val end = part.substring(dash + 1).toIntOrNull() ?: continue
                if (start < 1 || start > WeekPatterns.MAX_WEEKS || end < start) continue
                mask = mask or WeekPatterns.fromRange(start, minOf(end, WeekPatterns.MAX_WEEKS), oddEven)
                matched = true
            } else {
                val week = part.toIntOrNull() ?: continue
                if (week < 1 || week > WeekPatterns.MAX_WEEKS) continue
                matched = true
                val keep = when (oddEven) {
                    OddEven.ALL -> true
                    OddEven.ODD -> week % 2 == 1
                    OddEven.EVEN -> week % 2 == 0
                }
                if (keep) mask = mask or WeekPatterns.of(week)
            }
        }
        if (matched && mask != 0L) return mask
        if (defaultOddEven != OddEven.ALL) {
            return WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, defaultOddEven)
        }
        return null
    }

    /** 解析单行；无法识别返回 null */
    private fun parseLine(line: String): ImportedCourse? {
        var rest = line
        var startSection = -1
        var endSection = -1
        var dayOfWeek = -1

        // 1) 周次：整体解析后剥离，避免「1-16周,单周」里的标记残留在字段中
        val weekPattern = parseWeekPatternText(rest)
        rest = stripWeekFragments(rest)

        // 2) 节次：先区间，再单节
        val sectionRangeMatches = SECTION_RANGE_RE.findAll(rest).toList()
        if (sectionRangeMatches.isNotEmpty()) {
            val first = sectionRangeMatches.first()
            startSection = first.groupValues[1].toIntOrNull() ?: -1
            endSection = first.groupValues[2].toIntOrNull() ?: -1
            rest = removeAll(rest, sectionRangeMatches)
        } else {
            val singleMatches = SECTION_SINGLE_RE.findAll(rest).toList()
            if (singleMatches.isNotEmpty()) {
                startSection = singleMatches.first().groupValues[1].toIntOrNull() ?: -1
                endSection = startSection
                rest = removeAll(rest, singleMatches)
            }
        }

        // 3) 星期（放在最后，避免把「1-16周1-2节」里的「周1」误判成周一）
        val dayMatches = WEEKDAY_RE.findAll(rest).toList()
        if (dayMatches.isNotEmpty()) {
            dayOfWeek = weekdayValue(dayMatches.first().groupValues[1])
            rest = removeAll(rest, dayMatches)
        }

        if (dayOfWeek !in 1..7) return null
        if (startSection < 1) return null
        if (endSection < startSection) return null

        // 4) 剩余字段归类（契约 9.2）
        val fields = rest.split(SEPARATOR_RE)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !NOISE_RE.matches(it) }
        if (fields.isEmpty()) return null
        val classified = classifyFields(fields)
        if (classified.name.isEmpty()) return null

        val pattern = if (weekPattern != null) {
            weekPattern
        } else {
            // 契约 8.3：缺周次时按 1–30 周全周兜底
            WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, OddEven.ALL)
        }

        return ImportedCourse(
            name = classified.name,
            teacher = classified.teacher,
            location = classified.location,
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            endSection = endSection,
            weekPattern = pattern
        )
    }

    /**
     * 把周次相关片段从文本中剥离（整段 → 区间 → 枚举 → 单双标记）。
     *
     * 必须先走 [WEEK_FRAGMENT_RE] **整段**剥离，才能与解析口径对齐：`1-4,6-16周` 里只有末段带
     * 「周」，只跑区间正则会漏掉 `1-4`；残留的 `1-4` 随后会被 `classifyFields` 当成一个字段，
     * 把真正的课程名挤掉（用户会看到课程名变成编号）。
     */
    private fun stripWeekFragments(source: String): String {
        var result = removeAll(source, WEEK_FRAGMENT_RE.findAll(source).toList())
        result = removeAll(result, WEEK_RANGE_RE.findAll(result).toList())
        result = removeAll(result, WEEK_LIST_RE.findAll(result).toList())
        result = removeAll(result, WEEK_ODD_EVEN_RE.findAll(result).toList())
        return result
    }

    /**
     * 字段归类（契约 9.2）：取第一个命中者作地点，再取教师，其余最长为课程名。
     *
     * 地点与教师强特征都没有命中时，回退为「第一个片段即课程名」的旧行为（契约 9.2 兜底条款），
     * 保证启发式只提高命中率，不把原本可解析的行变成错误。
     */
    private fun classifyFields(fields: List<String>): FieldClassification {
        val placeIndex = fields.indexOfFirst { isPlaceLike(it) }
        val titleIndex = fields.indexOfFirst { TEACHER_TITLE_RE.matches(it) }

        if (placeIndex < 0 && titleIndex < 0) {
            return FieldClassification(
                name = fields[0],
                teacher = fields.getOrElse(1) { "" },
                location = fields.getOrElse(2) { "" }
            )
        }

        val candidates = fields.indices.filter { it != placeIndex }
        val nameIndex = candidates.maxByOrNull { fields[it].length } ?: -1
        if (nameIndex < 0) {
            return FieldClassification(name = fields[0], teacher = "", location = "")
        }

        var teacherIndex = candidates.firstOrNull { it != nameIndex && TEACHER_TITLE_RE.matches(fields[it]) }
        if (teacherIndex == null) {
            teacherIndex = candidates.firstOrNull { it != nameIndex && HAN_NAME_RE.matches(fields[it]) }
        }

        return FieldClassification(
            name = fields[nameIndex],
            teacher = if (teacherIndex != null) fields[teacherIndex] else "",
            location = if (placeIndex >= 0) fields[placeIndex] else ""
        )
    }

    /** 地点判定（契约 9.2 第 1 条） */
    private fun isPlaceLike(segment: String): Boolean {
        if (PLACE_KEYWORDS.any { segment.contains(it) }) return true
        if (ALNUM_PLACE_RE.matches(segment)) return true
        if (PURE_DIGIT_RE.matches(segment)) return true
        if (segment.contains("教") && (segment.any { it.isDigit() } || segment.contains("学楼"))) return true
        // 弱特征：不含汉字的短串以数字结尾（如「3-101」）；
        // 含汉字的串（如「大学英语2」）不判为地点，避免把带数字后缀的课程名误吞为地点。
        return segment.length <= PLACE_MAX_LENGTH &&
            !HAN_CHAR_RE.containsMatchIn(segment) &&
            DIGIT_TAIL_RE.containsMatchIn(segment)
    }

    /** 把已识别的片段统一替换为分隔符；从后往前替换，避免区间位移 */
    private fun removeAll(source: String, matches: List<MatchResult>): String {
        var result = source
        for (match in matches.asReversed()) {
            result = result.replaceRange(match.range, ",")
        }
        return result
    }

    private fun oddEvenOf(token: String): OddEven = when (token) {
        "单" -> OddEven.ODD
        "双" -> OddEven.EVEN
        else -> OddEven.ALL
    }

    private fun weekdayValue(token: String): Int = when (token) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "日", "天" -> 7
        else -> token.toIntOrNull() ?: -1
    }
}
