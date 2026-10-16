package com.xixi.timetable.data.pdf

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.util.zip.Inflater

/**
 * PDF 底层文本提取器：不依赖任何第三方库，也不依赖 `android.*`，因此可以直接在 JVM 单元测试里跑。
 *
 * 坐标语义（与 [PdfTextItem] 的约定一致）：
 * - 原点在页面**左下角**，x 向右、y 向上增长；
 * - [PdfTextItem.x] 是文本基线起点的横坐标，[PdfTextItem.y] 是**基线**的纵坐标；
 * - 页面 `/Rotate` 为 90/180/270 时，输出坐标已按旋转后的显示方向换算（见 [rotateItems]）。
 *
 * 容错边界：
 * - 单个对象、单个流、单条内容流指令解析失败都只跳过它，继续解析；
 * - 只有「完全拿不出任何页面」或「PDF 已加密 / 内容为空」才抛 [PdfParseException]；
 * - 任何实现细节异常（越界、格式错误等）都在内部捕获并转成跳过，不会泄漏给调用方。
 */
object PdfTextExtractor {

    /**
     * 解析 PDF 字节流，按页返回文本项。
     *
     * @param bytes PDF 文件内容
     * @return 每一页的文本项，顺序与页序一致；每页 items 按内容流出现顺序
     * @throws PdfParseException 内容为空、已加密、或完全解析不出页面时抛出
     */
    fun extractAllPages(bytes: ByteArray): List<PdfPageText> {
        if (bytes.isEmpty()) {
            throw PdfParseException("PDF 内容为空")
        }
        val document = try {
            parsePdfDocument(bytes)
        } catch (e: PdfParseException) {
            throw e
        } catch (e: Exception) {
            throw PdfParseException("PDF 结构解析失败：${e.message}", e)
        }
        if (document.encrypted) {
            throw PdfParseException("该 PDF 已加密，无法提取文本")
        }
        val pages = try {
            document.collectPages()
        } catch (e: Exception) {
            emptyList()
        }
        if (pages.isEmpty()) {
            throw PdfParseException("未能从该 PDF 中解析出任何页面")
        }
        val result = ArrayList<PdfPageText>(pages.size)
        for (page in pages) {
            val extraction = try {
                document.extractPage(page)
            } catch (e: Exception) {
                PageExtraction(emptyList(), emptyList())
            }
            result.add(
                PdfPageText(
                    width = page.width,
                    height = page.height,
                    items = extraction.items,
                    segments = extraction.segments
                )
            )
        }
        return result
    }
}

// =====================================================================================
// 基础常量
// =====================================================================================

/** 文本一律按单字节读取（PDF 的语法层是面向字节的）。 */
private val CHARSET_LATIN1: Charset = StandardCharsets.ISO_8859_1

/** UTF-16BE：UCS-2 编码的 Type0 字体（UniGB-UCS2-H 等）与 ToUnicode 目标串都靠它。 */
private val CHARSET_UTF16BE: Charset = StandardCharsets.UTF_16BE

/** WinAnsiEncoding（CP1252）；取不到时退回 Latin-1，保证不抛异常。 */
private val CHARSET_WINANSI: Charset = try {
    Charset.forName("windows-1252")
} catch (e: Exception) {
    CHARSET_LATIN1
}

/** /MediaBox 缺失时的兜底页面尺寸（A4，pt）。 */
private val DEFAULT_MEDIA_BOX = floatArrayOf(0f, 0f, 595f, 842f)

/** Form XObject 递归深度上限。 */
private const val MAX_FORM_DEPTH = 8

/** 页面树递归深度上限（防环）。 */
private const val MAX_PAGE_TREE_DEPTH = 16

/** 单份 PDF 最多解析的页数（防异常文件把内存吃光）。 */
private const val MAX_PAGES = 2000

/** 对象解析时的引用链长度上限。 */
private const val MAX_REF_DEPTH = 32

// =====================================================================================
// PDF 对象模型
// =====================================================================================

/** PDF 基础对象，只保留文本提取所需的形态。 */
private sealed class PdfObj {
    class Num(val value: Double) : PdfObj()
    class Str(val bytes: ByteArray) : PdfObj()
    class Name(val value: String) : PdfObj()
    class Bool(val value: Boolean) : PdfObj()
    object NullObj : PdfObj()
    class Arr(val items: List<PdfObj>) : PdfObj()
    class Dict(val map: Map<String, PdfObj>) : PdfObj()
    class Ref(val number: Int) : PdfObj()
    class Stream(val dict: PdfObj.Dict, val raw: ByteArray) : PdfObj()
}

private fun PdfObj?.asDict(): PdfObj.Dict? = this as? PdfObj.Dict

private fun PdfObj?.asArray(): List<PdfObj>? = (this as? PdfObj.Arr)?.items

private fun PdfObj?.asName(): String? = (this as? PdfObj.Name)?.value

private fun PdfObj?.asBytes(): ByteArray? = (this as? PdfObj.Str)?.bytes

private fun PdfObj?.asBool(): Boolean? = (this as? PdfObj.Bool)?.value

private fun PdfObj?.asDouble(): Double? = when (this) {
    is PdfObj.Num -> value
    else -> null
}

private fun PdfObj?.asInt(): Int? = asDouble()?.let { value ->
    if (value.isNaN() || value.isInfinite()) null else value.toInt()
}

private fun PdfObj?.asFloat(): Float? = asDouble()?.let { value ->
    if (value.isNaN() || value.isInfinite()) null else value.toFloat()
}

// =====================================================================================
// 词法与对象解析
// =====================================================================================

/** 面向字节的 PDF 词法读取器。 */
private class PdfLexer(val data: ByteArray, var pos: Int) {

    fun eof(): Boolean = pos >= data.size

    fun peekByte(): Int = if (pos < data.size) data[pos].toInt() and 0xFF else -1

    /** 跳过空白与 `%` 注释。 */
    fun skipWhitespace() {
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            if (c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00) {
                pos++
            } else if (c == 0x25) {
                while (pos < data.size) {
                    val n = data[pos].toInt() and 0xFF
                    if (n == 0x0A || n == 0x0D) break
                    pos++
                }
            } else {
                break
            }
        }
    }

    /** 读一个「普通词」（操作符或关键字），遇到分隔符停止。 */
    fun readWord(): String {
        skipWhitespace()
        val start = pos
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            if (c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00) break
            if (c == 0x2F || c == 0x28 || c == 0x29 || c == 0x3C || c == 0x3E ||
                c == 0x5B || c == 0x5D || c == 0x7B || c == 0x7D || c == 0x25
            ) {
                break
            }
            pos++
        }
        if (pos == start) return ""
        return String(data, start, pos - start, CHARSET_LATIN1)
    }
}

private fun hexValue(b: Int): Int = when {
    b >= 0x30 && b <= 0x39 -> b - 0x30
    b >= 0x41 && b <= 0x46 -> b - 0x41 + 10
    b >= 0x61 && b <= 0x66 -> b - 0x61 + 10
    else -> -1
}

private fun readNumberToken(lexer: PdfLexer): String? {
    lexer.skipWhitespace()
    val start = lexer.pos
    if (lexer.pos < lexer.data.size) {
        val c = lexer.data[lexer.pos].toInt() and 0xFF
        if (c == 0x2B || c == 0x2D) lexer.pos++
    }
    var seenDigit = false
    while (lexer.pos < lexer.data.size) {
        val c = lexer.data[lexer.pos].toInt() and 0xFF
        if (c >= 0x30 && c <= 0x39) {
            seenDigit = true
            lexer.pos++
        } else if (c == 0x2E) {
            lexer.pos++
        } else if (c == 0x65 || c == 0x45) {
            lexer.pos++
            if (lexer.pos < lexer.data.size) {
                val s = lexer.data[lexer.pos].toInt() and 0xFF
                if (s == 0x2B || s == 0x2D) lexer.pos++
            }
        } else {
            break
        }
    }
    if (!seenDigit) {
        lexer.pos = start
        return null
    }
    return String(lexer.data, start, lexer.pos - start, CHARSET_LATIN1)
}

private fun isIntegerToken(token: String): Boolean =
    token.isNotEmpty() && token.all { it in '0'..'9' || it == '-' || it == '+' }

/** 解析一个 PDF 对象；失败返回 null（调用方决定跳过）。 */
private fun parsePdfObject(lexer: PdfLexer): PdfObj? {
    lexer.skipWhitespace()
    if (lexer.eof()) return null
    val c = lexer.peekByte()
    return when {
        c == 0x2F -> PdfObj.Name(parsePdfName(lexer))
        c == 0x28 -> PdfObj.Str(parseLiteralString(lexer))
        c == 0x3C -> {
            val isDict = lexer.pos + 1 < lexer.data.size &&
                (lexer.data[lexer.pos + 1].toInt() and 0xFF) == 0x3C
            if (isDict) parsePdfDictionary(lexer) else PdfObj.Str(parseHexString(lexer))
        }
        c == 0x5B -> parsePdfArray(lexer)
        c == 0x2B || c == 0x2D || c == 0x2E || (c >= 0x30 && c <= 0x39) -> parseNumberOrRef(lexer)
        else -> {
            val word = lexer.readWord()
            when (word) {
                "true" -> PdfObj.Bool(true)
                "false" -> PdfObj.Bool(false)
                "null" -> PdfObj.NullObj
                else -> null
            }
        }
    }
}

private fun parsePdfName(lexer: PdfLexer): String {
    lexer.skipWhitespace()
    if (lexer.peekByte() == 0x2F) lexer.pos++
    val sb = StringBuilder()
    while (!lexer.eof()) {
        val b = lexer.peekByte()
        if (b == 0x20 || b == 0x0A || b == 0x0D || b == 0x09 || b == 0x0C || b == 0x00) break
        if (b == 0x2F || b == 0x28 || b == 0x29 || b == 0x3C || b == 0x3E ||
            b == 0x5B || b == 0x5D || b == 0x7B || b == 0x7D
        ) {
            break
        }
        if (b == 0x23 && lexer.pos + 2 < lexer.data.size) {
            val h1 = hexValue(lexer.data[lexer.pos + 1].toInt() and 0xFF)
            val h2 = hexValue(lexer.data[lexer.pos + 2].toInt() and 0xFF)
            if (h1 >= 0 && h2 >= 0) {
                sb.append(((h1 shl 4) + h2).toChar())
                lexer.pos += 3
                continue
            }
        }
        sb.append((b and 0xFF).toChar())
        lexer.pos++
    }
    return sb.toString()
}

/** 字面串 `(...)`：处理 `\n \r \t \b \f \( \) \\ \ddd` 与行尾续行。 */
private fun parseLiteralString(lexer: PdfLexer): ByteArray {
    val out = ByteArrayOutputStream()
    if (lexer.peekByte() != 0x28) return ByteArray(0)
    lexer.pos++
    var depth = 1
    while (!lexer.eof() && depth > 0) {
        val b = lexer.data[lexer.pos].toInt() and 0xFF
        lexer.pos++
        if (b == 0x5C) {
            if (lexer.eof()) break
            val e = lexer.data[lexer.pos].toInt() and 0xFF
            lexer.pos++
            when (e) {
                0x6E -> out.write(0x0A)
                0x72 -> out.write(0x0D)
                0x74 -> out.write(0x09)
                0x62 -> out.write(0x08)
                0x66 -> out.write(0x0C)
                0x28 -> out.write(0x28)
                0x29 -> out.write(0x29)
                0x5C -> out.write(0x5C)
                0x0D -> {
                    if (!lexer.eof() && (lexer.data[lexer.pos].toInt() and 0xFF) == 0x0A) lexer.pos++
                }
                0x0A -> Unit
                else -> {
                    if (e in 0x30..0x37) {
                        var value = e - 0x30
                        var count = 1
                        while (count < 3 && !lexer.eof()) {
                            val d = lexer.data[lexer.pos].toInt() and 0xFF
                            if (d < 0x30 || d > 0x37) break
                            value = value * 8 + (d - 0x30)
                            lexer.pos++
                            count++
                        }
                        out.write(value and 0xFF)
                    } else {
                        out.write(e)
                    }
                }
            }
        } else if (b == 0x28) {
            depth++
            out.write(b)
        } else if (b == 0x29) {
            depth--
            if (depth > 0) out.write(b)
        } else {
            out.write(b)
        }
    }
    return out.toByteArray()
}

/** 十六进制串 `<...>`（奇数位自动补 0）。 */
private fun parseHexString(lexer: PdfLexer): ByteArray {
    val out = ByteArrayOutputStream()
    if (lexer.peekByte() != 0x3C) return ByteArray(0)
    lexer.pos++
    var high = -1
    while (!lexer.eof()) {
        val b = lexer.data[lexer.pos].toInt() and 0xFF
        lexer.pos++
        if (b == 0x3E) break
        val v = hexValue(b)
        if (v < 0) continue
        if (high < 0) {
            high = v
        } else {
            out.write((high shl 4) + v)
            high = -1
        }
    }
    if (high >= 0) out.write(high shl 4)
    return out.toByteArray()
}

private fun parsePdfArray(lexer: PdfLexer): PdfObj {
    val items = ArrayList<PdfObj>()
    lexer.skipWhitespace()
    if (lexer.peekByte() != 0x5B) return PdfObj.Arr(items)
    lexer.pos++
    while (!lexer.eof()) {
        lexer.skipWhitespace()
        if (lexer.peekByte() == 0x5D) {
            lexer.pos++
            break
        }
        val before = lexer.pos
        val obj = parsePdfObject(lexer)
        if (obj == null) {
            if (lexer.pos == before) lexer.pos++ else Unit
            continue
        }
        items.add(obj)
    }
    return PdfObj.Arr(items)
}

private fun parsePdfDictionary(lexer: PdfLexer): PdfObj {
    val map = HashMap<String, PdfObj>()
    lexer.skipWhitespace()
    if (lexer.peekByte() != 0x3C) return PdfObj.Dict(map)
    lexer.pos++
    if (lexer.peekByte() == 0x3C) lexer.pos++
    while (!lexer.eof()) {
        lexer.skipWhitespace()
        if (lexer.peekByte() == 0x3E) {
            lexer.pos++
            if (lexer.peekByte() == 0x3E) lexer.pos++
            break
        }
        if (lexer.peekByte() != 0x2F) {
            val before = lexer.pos
            lexer.pos++
            if (lexer.pos == before) break
            continue
        }
        val key = parsePdfName(lexer)
        val value = parsePdfObject(lexer)
        if (value == null) continue
        map[key] = value
    }
    return PdfObj.Dict(map)
}

private fun parseNumberOrRef(lexer: PdfLexer): PdfObj {
    val first = readNumberToken(lexer)
    if (first == null) {
        lexer.readWord()
        return PdfObj.NullObj
    }
    if (isIntegerToken(first)) {
        val afterFirst = lexer.pos
        val second = readNumberToken(lexer)
        if (second != null && isIntegerToken(second)) {
            val afterSecond = lexer.pos
            val word = lexer.readWord()
            if (word == "R") {
                val number = first.toIntOrNull()
                if (number != null) return PdfObj.Ref(number)
            }
            lexer.pos = afterSecond
        }
        lexer.pos = afterFirst
    }
    return PdfObj.Num(first.toDoubleOrNull() ?: 0.0)
}

/** 解析 `N G obj ... endobj`，并在对象为字典且后随 `stream` 时读出流数据。 */
private fun parseIndirectObjectAt(data: ByteArray, offset: Int): PdfObj? {
    if (offset < 0 || offset >= data.size) return null
    val lexer = PdfLexer(data, offset)
    val number = lexer.readWord().toIntOrNull() ?: return null
    if (number <= 0) return null
    val generation = lexer.readWord().toIntOrNull() ?: return null
    if (generation < 0) return null
    if (lexer.readWord() != "obj") return null

    val obj = parsePdfObject(lexer) ?: return null
    if (obj !is PdfObj.Dict) return obj

    val afterDict = lexer.pos
    val keyword = lexer.readWord()
    if (keyword != "stream") {
        lexer.pos = afterDict
        return obj
    }
    // stream 关键字后面必须跟 CRLF 或 LF
    if (lexer.pos < data.size && (data[lexer.pos].toInt() and 0xFF) == 0x0D) lexer.pos++
    if (lexer.pos < data.size && (data[lexer.pos].toInt() and 0xFF) == 0x0A) lexer.pos++
    val start = lexer.pos

    val declaredLength = obj.map["Length"]?.asInt()
    if (declaredLength != null && declaredLength >= 0 && start + declaredLength <= data.size) {
        return PdfObj.Stream(obj, data.copyOfRange(start, start + declaredLength))
    }
    val end = indexOfAscii(data, "endstream", start)
    if (end < 0) return obj
    var trimmed = end
    if (trimmed > start && (data[trimmed - 1].toInt() and 0xFF) == 0x0A) trimmed--
    if (trimmed > start && (data[trimmed - 1].toInt() and 0xFF) == 0x0D) trimmed--
    return PdfObj.Stream(obj, data.copyOfRange(start, trimmed))
}

private fun indexOfAscii(data: ByteArray, needle: String, from: Int): Int {
    val pattern = needle.toByteArray(CHARSET_LATIN1)
    if (pattern.isEmpty()) return -1
    var i = from.coerceAtLeast(0)
    while (i + pattern.size <= data.size) {
        var match = true
        var j = 0
        while (j < pattern.size) {
            if (data[i + j] != pattern[j]) {
                match = false
                break
            }
            j++
        }
        if (match) return i
        i++
    }
    return -1
}

private fun lastIndexOfAscii(data: ByteArray, needle: String, from: Int): Int {
    val pattern = needle.toByteArray(CHARSET_LATIN1)
    if (pattern.isEmpty()) return -1
    var i = minOf(from, data.size - pattern.size)
    while (i >= 0) {
        var match = true
        var j = 0
        while (j < pattern.size) {
            if (data[i + j] != pattern[j]) {
                match = false
                break
            }
            j++
        }
        if (match) return i
        i--
    }
    return -1
}

// =====================================================================================
// 流滤镜
// =====================================================================================

private fun decodeStream(document: PdfDocument, stream: PdfObj.Stream): ByteArray? {
    val dict = stream.dict
    val filters = collectFilterNames(document, dict.map["Filter"] ?: dict.map["F"])
    val parms = collectDecodeParms(document, dict.map["DecodeParms"] ?: dict.map["DP"])
    var data = stream.raw
    for (index in filters.indices) {
        val filter = filters[index]
        data = when (filter) {
            "FlateDecode", "Fl" -> inflateBytes(data) ?: return null
            "ASCIIHexDecode", "AHx" -> asciiHexDecode(data)
            "ASCII85Decode", "A85" -> ascii85Decode(data)
            "RunLengthDecode", "RL" -> runLengthDecode(data)
            "DCTDecode", "DCT", "JPXDecode", "CCITTFaxDecode", "CCF" -> return null
            else -> return null
        }
        val parm = parms.getOrNull(index)?.asDict()
        if (parm != null && (filter == "FlateDecode" || filter == "Fl")) {
            data = applyPredictor(document, data, parm)
        }
    }
    return data
}

private fun collectFilterNames(document: PdfDocument, value: PdfObj?): List<String> {
    val resolved = document.resolve(value)
    return when (resolved) {
        is PdfObj.Name -> listOf(resolved.value)
        is PdfObj.Arr -> resolved.items.mapNotNull { document.resolve(it).asName() }
        else -> emptyList()
    }
}

private fun collectDecodeParms(document: PdfDocument, value: PdfObj?): List<PdfObj> {
    val resolved = document.resolve(value)
    return when (resolved) {
        is PdfObj.Arr -> resolved.items
        is PdfObj.Dict -> listOf(resolved)
        else -> emptyList()
    }
}

private fun inflateBytes(input: ByteArray): ByteArray? {
    val inflater = Inflater()
    try {
        inflater.setInput(input)
        val out = ByteArrayOutputStream(input.size.coerceAtLeast(256) * 2)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val produced = inflater.inflate(buffer)
            if (produced <= 0) break
            out.write(buffer, 0, produced)
        }
        return out.toByteArray()
    } catch (e: Exception) {
        return null
    } finally {
        inflater.end()
    }
}

private fun asciiHexDecode(input: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var high = -1
    for (b in input) {
        val v = hexValue(b.toInt() and 0xFF)
        if (v < 0) {
            val c = b.toInt() and 0xFF
            if (c == 0x3E) break
            continue
        }
        if (high < 0) {
            high = v
        } else {
            out.write((high shl 4) + v)
            high = -1
        }
    }
    if (high >= 0) out.write(high shl 4)
    return out.toByteArray()
}

private fun ascii85Decode(input: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    val group = IntArray(5)
    var count = 0
    var index = 0
    while (index < input.size) {
        val c = input[index].toInt() and 0xFF
        index++
        if (c == 0x7E) break
        if (c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00) continue
        if (c == 0x7A && count == 0) {
            out.write(0)
            out.write(0)
            out.write(0)
            out.write(0)
            continue
        }
        if (c < 0x21 || c > 0x75) continue
        group[count] = c - 0x21
        count++
        if (count == 5) {
            writeAscii85Group(out, group, 4)
            count = 0
        }
    }
    if (count > 0) {
        for (i in count until 5) group[i] = 84
        writeAscii85Group(out, group, count - 1)
    }
    return out.toByteArray()
}

private fun writeAscii85Group(out: ByteArrayOutputStream, group: IntArray, byteCount: Int) {
    var value = 0L
    for (i in 0 until 5) {
        value = value * 85L + group[i].toLong()
    }
    val bytes = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
    val count = byteCount.coerceIn(0, 4)
    out.write(bytes, 0, count)
}

private fun runLengthDecode(input: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var index = 0
    while (index < input.size) {
        val length = input[index].toInt() and 0xFF
        index++
        if (length == 128) break
        if (length < 128) {
            val count = length + 1
            val end = minOf(index + count, input.size)
            out.write(input, index, end - index)
            index += count
        } else {
            if (index >= input.size) break
            val value = input[index].toInt() and 0xFF
            index++
            val count = 257 - length
            for (i in 0 until count) out.write(value)
        }
    }
    return out.toByteArray()
}

private fun applyPredictor(document: PdfDocument, data: ByteArray, parms: PdfObj.Dict): ByteArray {
    val predictor = document.resolve(parms.map["Predictor"]).asInt() ?: 1
    if (predictor <= 1) return data
    val colors = (document.resolve(parms.map["Colors"]).asInt() ?: 1).coerceAtLeast(1)
    val bitsPerComponent = (document.resolve(parms.map["BitsPerComponent"]).asInt() ?: 8).coerceAtLeast(1)
    val columns = (document.resolve(parms.map["Columns"]).asInt() ?: 1).coerceAtLeast(1)
    return if (predictor == 2) {
        tiffPredictor(data, colors, bitsPerComponent, columns)
    } else {
        pngPredictor(data, colors, bitsPerComponent, columns)
    }
}

private fun paethPredictor(left: Int, up: Int, upLeft: Int): Int {
    val p = left + up - upLeft
    val pa = kotlin.math.abs(p - left)
    val pb = kotlin.math.abs(p - up)
    val pc = kotlin.math.abs(p - upLeft)
    return when {
        pa <= pb && pa <= pc -> left
        pb <= pc -> up
        else -> upLeft
    }
}

private fun pngPredictor(data: ByteArray, colors: Int, bitsPerComponent: Int, columns: Int): ByteArray {
    if (bitsPerComponent != 8) return data
    val bytesPerPixel = colors
    val rowLength = columns * colors
    if (rowLength <= 0) return data
    val out = ByteArrayOutputStream(data.size)
    val previous = ByteArray(rowLength)
    var pos = 0
    while (pos < data.size) {
        val filterType = data[pos].toInt() and 0xFF
        pos++
        if (pos + rowLength > data.size) break
        val row = data.copyOfRange(pos, pos + rowLength)
        pos += rowLength
        for (i in row.indices) {
            val raw = row[i].toInt() and 0xFF
            val left = if (i >= bytesPerPixel) row[i - bytesPerPixel].toInt() and 0xFF else 0
            val up = previous[i].toInt() and 0xFF
            val upLeft = if (i >= bytesPerPixel) previous[i - bytesPerPixel].toInt() and 0xFF else 0
            val value = when (filterType) {
                0 -> raw
                1 -> raw + left
                2 -> raw + up
                3 -> raw + ((left + up) / 2)
                4 -> raw + paethPredictor(left, up, upLeft)
                else -> raw
            }
            row[i] = (value and 0xFF).toByte()
        }
        out.write(row, 0, row.size)
        System.arraycopy(row, 0, previous, 0, rowLength)
    }
    return out.toByteArray()
}

private fun tiffPredictor(data: ByteArray, colors: Int, bitsPerComponent: Int, columns: Int): ByteArray {
    if (bitsPerComponent != 8) return data
    val rowLength = columns * colors
    if (rowLength <= 0) return data
    val out = data.copyOf()
    var pos = 0
    while (pos + rowLength <= out.size) {
        for (i in colors until rowLength) {
            val left = out[pos + i - colors].toInt() and 0xFF
            val current = out[pos + i].toInt() and 0xFF
            out[pos + i] = ((current + left) and 0xFF).toByte()
        }
        pos += rowLength
    }
    return out
}

// =====================================================================================
// 文档：交叉引用表 / 对象流 / 页面树
// =====================================================================================

/** 交叉引用条目。 */
private sealed class PdfXrefEntry {
    class Offset(val offset: Int) : PdfXrefEntry()
    class InStream(val streamNumber: Int, val index: Int) : PdfXrefEntry()
}

private class PdfXrefSection(val entries: Map<Int, PdfXrefEntry>, val trailer: PdfObj.Dict?)

private class PdfDocument(
    val data: ByteArray,
    val objects: HashMap<Int, PdfObj>,
    val trailer: PdfObj.Dict?,
    val encrypted: Boolean
) {

    /** 解开引用链；失败返回 null。 */
    fun resolve(value: PdfObj?): PdfObj? {
        var current = value
        var guard = 0
        while (current is PdfObj.Ref && guard < MAX_REF_DEPTH) {
            current = objects[current.number]
            guard++
        }
        return current
    }

    /** 按页序收集页面（继承父节点的 MediaBox / Resources / Rotate）。 */
    fun collectPages(): List<PdfPageInfo> {
        val root = resolve(trailer?.map?.get("Root")).asDict() ?: return emptyList()
        val pagesNode = root.map["Pages"] ?: return emptyList()
        val out = ArrayList<PdfPageInfo>()
        collectPagesRecursive(pagesNode, PageInheritance(null, null, null), out, 0)
        return out
    }

    private fun collectPagesRecursive(
        node: PdfObj?,
        inherited: PageInheritance,
        out: MutableList<PdfPageInfo>,
        depth: Int
    ) {
        if (depth > MAX_PAGE_TREE_DEPTH || out.size >= MAX_PAGES) return
        val dict = resolve(node).asDict() ?: return
        val resources = resolve(dict.map["Resources"]).asDict() ?: inherited.resources
        val mediaBox = readFloatArray(resolve(dict.map["MediaBox"])) ?: inherited.mediaBox
        val rotate = resolve(dict.map["Rotate"]).asInt() ?: inherited.rotate
        val current = PageInheritance(resources, mediaBox, rotate)

        val kids = resolve(dict.map["Kids"]).asArray()
        val type = resolve(dict.map["Type"]).asName()
        if (kids != null && (type == null || type == "Pages")) {
            for (kid in kids) {
                collectPagesRecursive(kid, current, out, depth + 1)
                if (out.size >= MAX_PAGES) return
            }
            return
        }
        val box = current.mediaBox ?: DEFAULT_MEDIA_BOX
        val width = box[2] - box[0]
        val height = box[3] - box[1]
        val normalizedRotate = (((current.rotate ?: 0) % 360) + 360) % 360
        out.add(
            PdfPageInfo(
                dict = dict,
                resources = current.resources,
                width = if (width > 0f) width else DEFAULT_MEDIA_BOX[2],
                height = if (height > 0f) height else DEFAULT_MEDIA_BOX[3],
                rotate = normalizedRotate,
                mediaBox = box
            )
        )
    }

    /** 解析一页的全部内容流，返回带旋转换算后的文本项与表格线段。 */
    fun extractPage(page: PdfPageInfo): PageExtraction {
        val contents = resolve(page.dict.map["Contents"])
        val streams = ArrayList<PdfObj.Stream>()
        when (contents) {
            is PdfObj.Stream -> streams.add(contents)
            is PdfObj.Arr -> {
                for (item in contents.items) {
                    val resolved = resolve(item)
                    if (resolved is PdfObj.Stream) streams.add(resolved)
                }
            }
            else -> Unit
        }
        val collectedItems = ArrayList<PdfTextItem>()
        val collectedSegments = ArrayList<PdfLineSegment>()
        val interpreter = ContentInterpreter(this, collectedItems, collectedSegments)
        for (stream in streams) {
            val decoded = decodeStream(this, stream) ?: continue
            try {
                interpreter.run(decoded, page.resources, 0)
            } catch (e: Exception) {
                // 单条内容流失败不影响其它内容流
            }
        }
        return PageExtraction(
            items = rotateItems(collectedItems, page),
            segments = rotateSegments(collectedSegments, page)
        )
    }
}

/** 一页的提取结果：文本项 + 描边线段（都已按 `/Rotate` 换算）。 */
private class PageExtraction(
    val items: List<PdfTextItem>,
    val segments: List<PdfLineSegment>
)

private class PageInheritance(
    val resources: PdfObj.Dict?,
    val mediaBox: FloatArray?,
    val rotate: Int?
)

private class PdfPageInfo(
    val dict: PdfObj.Dict,
    val resources: PdfObj.Dict?,
    val width: Float,
    val height: Float,
    val rotate: Int,
    val mediaBox: FloatArray
)

private fun readFloatArray(value: PdfObj?): FloatArray? {
    val items = (value as? PdfObj.Arr)?.items ?: return null
    if (items.size < 4) return null
    val out = FloatArray(items.size)
    for (i in items.indices) {
        out[i] = (items[i] as? PdfObj.Num)?.value?.toFloat() ?: return null
    }
    return out
}

/** 页面旋转换算：把用户空间坐标换成旋转后的显示坐标。 */
private fun rotateItems(items: List<PdfTextItem>, page: PdfPageInfo): List<PdfTextItem> {
    if (page.rotate == 0) return items
    val w = page.width
    val h = page.height
    return items.map { item ->
        when (page.rotate) {
            90 -> PdfTextItem(item.text, item.y, w - item.x, item.fontSize)
            180 -> PdfTextItem(item.text, w - item.x, h - item.y, item.fontSize)
            270 -> PdfTextItem(item.text, h - item.y, item.x, item.fontSize)
            else -> item
        }
    }
}

/** 线段与文本项做**完全相同**的旋转，保证两者仍在同一坐标系里可直接比较。 */
private fun rotateSegments(segments: List<PdfLineSegment>, page: PdfPageInfo): List<PdfLineSegment> {
    if (page.rotate == 0) return segments
    val w = page.width
    val h = page.height
    return segments.map { segment ->
        when (page.rotate) {
            90 -> PdfLineSegment(segment.y1, w - segment.x1, segment.y2, w - segment.x2)
            180 -> PdfLineSegment(w - segment.x1, h - segment.y1, w - segment.x2, h - segment.y2)
            270 -> PdfLineSegment(h - segment.y1, segment.x1, h - segment.y2, segment.x2)
            else -> segment
        }
    }
}

// =====================================================================================
// 文档装载：startxref → xref 表 / xref 流 → 顺序扫描兜底 → 对象流展开
// =====================================================================================

private fun parsePdfDocument(data: ByteArray): PdfDocument {
    val objects = HashMap<Int, PdfObj>()
    val trailers = ArrayList<PdfObj.Dict>()
    val inStreamEntries = ArrayList<Pair<Int, Pair<Int, Int>>>()

    val startXref = findStartXref(data)
    val pendingOffsets = ArrayList<Int>()
    if (startXref != null) pendingOffsets.add(startXref)

    var visitedSections = 0
    while (pendingOffsets.isNotEmpty() && visitedSections < 64) {
        visitedSections++
        val offset = pendingOffsets.removeAt(0)
        val section = try {
            parseXrefAt(data, offset)
        } catch (e: Exception) {
            null
        } ?: continue
        for ((number, entry) in section.entries) {
            if (objects.containsKey(number)) continue
            when (entry) {
                is PdfXrefEntry.Offset -> {
                    val obj = try {
                        parseIndirectObjectAt(data, entry.offset)
                    } catch (e: Exception) {
                        null
                    }
                    if (obj != null) objects[number] = obj
                }
                is PdfXrefEntry.InStream -> inStreamEntries.add(number to (entry.streamNumber to entry.index))
            }
        }
        val trailer = section.trailer
        if (trailer != null) {
            trailers.add(trailer)
            val prev = (trailer.map["Prev"] as? PdfObj.Num)?.value?.toInt()
            if (prev != null && prev > 0 && prev < data.size && !pendingOffsets.contains(prev)) {
                pendingOffsets.add(prev)
            }
            val xrefStm = (trailer.map["XRefStm"] as? PdfObj.Num)?.value?.toInt()
            if (xrefStm != null && xrefStm > 0 && xrefStm < data.size && !pendingOffsets.contains(xrefStm)) {
                pendingOffsets.add(xrefStm)
            }
        }
    }

    // 顺序扫描兜底：xref 拿不到（或只拿到一部分）时把缺失的对象补出来
    scanIndirectObjects(data, objects)

    val primaryTrailer = trailers.firstOrNull()
    val encrypted = primaryTrailer?.map?.containsKey("Encrypt") == true ||
        trailers.any { it.map.containsKey("Encrypt") }

    val document = PdfDocument(data, objects, primaryTrailer, encrypted)

    // 展开对象流（/Type/ObjStm）
    expandObjectStreams(document, inStreamEntries)

    return document
}

private fun findStartXref(data: ByteArray): Int? {
    val index = lastIndexOfAscii(data, "startxref", data.size - 1)
    if (index < 0) return null
    val lexer = PdfLexer(data, index + "startxref".length)
    val token = readNumberToken(lexer) ?: return null
    val offset = token.toIntOrNull() ?: return null
    return if (offset in 0 until data.size) offset else null
}

private fun parseXrefAt(data: ByteArray, offset: Int): PdfXrefSection? {
    if (offset < 0 || offset >= data.size) return null
    val lexer = PdfLexer(data, offset)
    lexer.skipWhitespace()
    val save = lexer.pos
    val keyword = lexer.readWord()
    if (keyword == "xref") {
        lexer.pos = save
        return parseClassicXref(data, offset)
    }
    lexer.pos = save
    val obj = parseIndirectObjectAt(data, offset) ?: return null
    if (obj is PdfObj.Stream) {
        return parseXrefStream(obj)
    }
    return null
}

private fun parseClassicXref(data: ByteArray, offset: Int): PdfXrefSection? {
    val lexer = PdfLexer(data, offset)
    if (lexer.readWord() != "xref") return null
    val entries = HashMap<Int, PdfXrefEntry>()
    var trailer: PdfObj.Dict? = null
    var guard = 0
    while (!lexer.eof() && guard < 1_000_000) {
        guard++
        lexer.skipWhitespace()
        val save = lexer.pos
        if (lexer.readWord() == "trailer") {
            trailer = parsePdfObject(lexer).asDict()
            break
        }
        lexer.pos = save
        val start = readNumberToken(lexer)?.toIntOrNull() ?: break
        val count = readNumberToken(lexer)?.toIntOrNull() ?: break
        if (count < 0 || count > 1_000_000) break
        for (i in 0 until count) {
            lexer.skipWhitespace()
            val entryOffsetToken = readNumberToken(lexer) ?: break
            val generationToken = readNumberToken(lexer) ?: break
            if (generationToken.isEmpty()) break
            val flag = lexer.readWord()
            if (flag == "n") {
                val entryOffset = entryOffsetToken.toIntOrNull()
                if (entryOffset != null && entryOffset in 0 until data.size) {
                    entries[start + i] = PdfXrefEntry.Offset(entryOffset)
                }
            }
        }
    }
    return PdfXrefSection(entries, trailer)
}

private fun parseXrefStream(stream: PdfObj.Stream): PdfXrefSection? {
    val dict = stream.dict
    if (dict.map["Type"]?.asName() != "XRef" && dict.map["W"] == null) return null

    val fakeDocument = PdfDocument(ByteArray(0), HashMap(), null, false)
    val decoded = decodeStream(fakeDocument, stream) ?: return null

    val widths = readFloatArray(dict.map["W"])?.map { it.toInt() } ?: return null
    if (widths.size < 3) return null
    val w0 = widths[0].coerceAtLeast(0)
    val w1 = widths[1].coerceAtLeast(0)
    val w2 = widths[2].coerceAtLeast(0)
    val entrySize = w0 + w1 + w2
    if (entrySize <= 0) return null

    val indexPairs = readFloatArray(dict.map["Index"])?.map { it.toInt() }
    val ranges = ArrayList<Pair<Int, Int>>()
    if (indexPairs != null && indexPairs.size >= 2) {
        var i = 0
        while (i + 1 < indexPairs.size) {
            ranges.add(indexPairs[i] to indexPairs[i + 1])
            i += 2
        }
    } else {
        val size = (dict.map["Size"] as? PdfObj.Num)?.value?.toInt() ?: 0
        ranges.add(0 to size)
    }

    val entries = HashMap<Int, PdfXrefEntry>()
    var cursor = 0
    for ((start, count) in ranges) {
        for (i in 0 until count) {
            if (cursor + entrySize > decoded.size) return PdfXrefSection(entries, dict)
            val type = if (w0 == 0) 1 else readBigEndian(decoded, cursor, w0)
            val field2 = if (w1 == 0) 0 else readBigEndian(decoded, cursor + w0, w1)
            val field3 = if (w2 == 0) 0 else readBigEndian(decoded, cursor + w0 + w1, w2)
            cursor += entrySize
            val number = start + i
            when (type) {
                1 -> if (field3 in 0 until decoded.size || field3 > 0) {
                    entries[number] = PdfXrefEntry.Offset(field3)
                }
                2 -> entries[number] = PdfXrefEntry.InStream(field2, field3)
                else -> Unit
            }
        }
    }
    return PdfXrefSection(entries, dict)
}

private fun readBigEndian(data: ByteArray, offset: Int, width: Int): Int {
    var value = 0
    for (i in 0 until width) {
        val index = offset + i
        if (index < 0 || index >= data.size) return value
        value = (value shl 8) or (data[index].toInt() and 0xFF)
    }
    return value
}

private fun isWhitespaceByte(value: Int): Boolean =
    value == 0x20 || value == 0x0A || value == 0x0D || value == 0x09 || value == 0x0C || value == 0x00

/** 顺序扫描整个文件的 `N G obj`，把 xref 没覆盖到的对象补进表里（健壮性兜底）。 */
private fun scanIndirectObjects(data: ByteArray, objects: HashMap<Int, PdfObj>) {
    var searchFrom = 0
    while (searchFrom < data.size) {
        val keyword = indexOfAscii(data, "obj", searchFrom)
        if (keyword < 0) break
        searchFrom = keyword + 3

        // `obj` 之前必须是空白，否则可能只是 endobj 之类的词的一部分
        if (keyword == 0) continue
        if (!isWhitespaceByte(data[keyword - 1].toInt() and 0xFF)) continue

        var cursor = keyword - 1
        while (cursor >= 0 && isWhitespaceByte(data[cursor].toInt() and 0xFF)) cursor--
        val generationEnd = cursor
        while (cursor >= 0 && (data[cursor].toInt() and 0xFF) in 0x30..0x39) cursor--
        if (cursor == generationEnd) continue
        val generation = String(data, cursor + 1, generationEnd - cursor, CHARSET_LATIN1).toIntOrNull()
            ?: continue
        if (generation < 0 || generation > 65535) continue

        var spaces = 0
        while (cursor >= 0 && isWhitespaceByte(data[cursor].toInt() and 0xFF)) {
            cursor--
            spaces++
        }
        if (spaces == 0) continue
        val numberEnd = cursor
        while (cursor >= 0 && (data[cursor].toInt() and 0xFF) in 0x30..0x39) cursor--
        if (cursor == numberEnd) continue
        val headerStart = cursor + 1
        val number = String(data, headerStart, numberEnd - cursor, CHARSET_LATIN1).toIntOrNull() ?: continue
        if (number <= 0 || objects.containsKey(number)) continue
        val obj = try {
            parseIndirectObjectAt(data, headerStart)
        } catch (e: Exception) {
            null
        }
        if (obj != null) objects[number] = obj
    }
}

private fun expandObjectStreams(document: PdfDocument, entries: List<Pair<Int, Pair<Int, Int>>>) {
    if (entries.isEmpty()) return
    val grouped = HashMap<Int, MutableList<Pair<Int, Int>>>()
    for ((number, location) in entries) {
        grouped.getOrPut(location.first) { ArrayList() }.add(number to location.second)
    }
    for ((streamNumber, wanted) in grouped) {
        val stream = document.objects[streamNumber] as? PdfObj.Stream ?: continue
        val decoded = decodeStream(document, stream) ?: continue
        val n = document.resolve(stream.dict.map["N"]).asInt() ?: continue
        val first = document.resolve(stream.dict.map["First"]).asInt() ?: continue
        val offsets = HashMap<Int, Int>()
        val headerLexer = PdfLexer(decoded, 0)
        for (i in 0 until n) {
            val objectNumber = readNumberToken(headerLexer)?.toIntOrNull() ?: break
            val relative = readNumberToken(headerLexer)?.toIntOrNull() ?: break
            offsets[objectNumber] = relative
        }
        for ((objectNumber, _) in wanted) {
            if (document.objects.containsKey(objectNumber)) continue
            val relative = offsets[objectNumber] ?: continue
            val start = first + relative
            if (start < 0 || start >= decoded.size) continue
            val obj = try {
                parsePdfObject(PdfLexer(decoded, start))
            } catch (e: Exception) {
                null
            }
            if (obj != null) document.objects[objectNumber] = obj
        }
    }
}

// =====================================================================================
// 字体编码
// =====================================================================================

/**
 * 文本解码器：
 * - [MODE_CMAP]：用 ToUnicode CMap（按 [codeLength] 字节切分查表）；
 * - [MODE_UTF16]：按 UTF-16BE 解码（Type0 / UniGB-UCS2-H 等）；
 * - [MODE_WINANSI]：简单字体按 WinAnsiEncoding 逐字节解码；
 * - [MODE_AUTO]：先试 UTF-16BE，结果不可用则整项丢弃。
 */
private class TextDecoder(
    private val mode: Int,
    private val cmap: Map<Int, String>?,
    private val codeLength: Int
) {
    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return when (mode) {
            MODE_CMAP -> decodeByCMap(bytes)
            MODE_UTF16 -> decodeUtf16(bytes)
            MODE_WINANSI -> decodeWinAnsi(bytes)
            else -> decodeUtf16(bytes)?.takeIf { it.isNotEmpty() && !it.contains('\uFFFD') }
        }
    }

    private fun decodeByCMap(bytes: ByteArray): String? {
        val map = cmap ?: return null
        val step = codeLength.coerceAtLeast(1)
        val sb = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            var matched: String? = null
            var used = 0
            val maxWidth = minOf(step, bytes.size - index)
            for (width in maxWidth downTo 1) {
                val code = readBigEndian(bytes, index, width)
                val value = map[code]
                if (value != null) {
                    matched = value
                    used = width
                    break
                }
            }
            if (matched != null) {
                sb.append(matched)
                index += used
            } else {
                index += 1
            }
        }
        val text = sb.toString()
        return if (text.isEmpty()) null else text
    }

    private fun decodeUtf16(bytes: ByteArray): String? {
        if (bytes.size < 2) return null
        val usable = bytes.size - (bytes.size % 2)
        if (usable <= 0) return null
        val text = String(bytes, 0, usable, CHARSET_UTF16BE)
        return if (text.isEmpty()) null else text
    }

    private fun decodeWinAnsi(bytes: ByteArray): String? {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            if (code == 0) continue
            val single = String(byteArrayOf(b), CHARSET_WINANSI)
            if (single.isEmpty()) continue
            if (single[0] == '\uFFFD') continue
            sb.append(single)
        }
        val text = sb.toString()
        return if (text.isEmpty()) null else text
    }

    companion object {
        const val MODE_CMAP = 0
        const val MODE_UTF16 = 1
        const val MODE_WINANSI = 2
        const val MODE_AUTO = 3
    }
}

private fun createTextDecoder(document: PdfDocument, font: PdfObj.Dict): TextDecoder {
    val subtype = document.resolve(font.map["Subtype"]).asName()
    val encodingValue = document.resolve(font.map["Encoding"])
    val encodingName = encodingValue.asName()
    val encodingDict = encodingValue.asDict()

    val toUnicode = document.resolve(font.map["ToUnicode"]) as? PdfObj.Stream
    if (toUnicode != null) {
        val cmapData = decodeStream(document, toUnicode)
        if (cmapData != null) {
            val parsed = parseToUnicodeCMap(cmapData)
            if (parsed.entries.isNotEmpty()) {
                return TextDecoder(TextDecoder.MODE_CMAP, parsed.entries, parsed.codeLength)
            }
        }
    }

    if (subtype == "Type0") {
        return TextDecoder(TextDecoder.MODE_UTF16, null, 2)
    }
    if (subtype == "TrueType" || subtype == "Type1" || subtype == "MMType1" || subtype == "Type3") {
        return TextDecoder(TextDecoder.MODE_WINANSI, null, 1)
    }
    if (encodingName != null || encodingDict != null) {
        return TextDecoder(TextDecoder.MODE_WINANSI, null, 1)
    }
    return TextDecoder(TextDecoder.MODE_AUTO, null, 2)
}

// =====================================================================================
// ToUnicode CMap 解析
// =====================================================================================

private class ToUnicodeResult(val entries: Map<Int, String>, val codeLength: Int)

private val CMapHexTokenRe = Regex("<([0-9A-Fa-f\\s]*)>")
private val CMapRangeEntryRe = Regex(
    "<([0-9A-Fa-f\\s]+)>\\s*<([0-9A-Fa-f\\s]+)>\\s*(?:<([0-9A-Fa-f\\s]+)>|\\[([^\\]]*)\\])"
)

private fun parseToUnicodeCMap(data: ByteArray): ToUnicodeResult {
    val text = String(data, CHARSET_LATIN1)
    val entries = HashMap<Int, String>()
    var maxLength = 1

    for (block in extractCMapBlocks(text, "beginbfchar", "endbfchar")) {
        val tokens = CMapHexTokenRe.findAll(block).map { unhexToBytes(it.groupValues[1]) }.toList()
        var index = 0
        while (index + 1 < tokens.size) {
            val source = tokens[index]
            val target = tokens[index + 1]
            index += 2
            if (source.isEmpty() || target.isEmpty()) continue
            val key = bytesToCode(source)
            val value = String(target, CHARSET_UTF16BE)
            if (value.isNotEmpty()) {
                entries[key] = value
                maxLength = maxOf(maxLength, source.size)
            }
        }
    }

    for (block in extractCMapBlocks(text, "beginbfrange", "endbfrange")) {
        for (match in CMapRangeEntryRe.findAll(block)) {
            val lowBytes = unhexToBytes(match.groupValues[1])
            val highBytes = unhexToBytes(match.groupValues[2])
            if (lowBytes.isEmpty() || highBytes.isEmpty()) continue
            val low = bytesToCode(lowBytes)
            val high = bytesToCode(highBytes)
            maxLength = maxOf(maxLength, lowBytes.size, highBytes.size)
            val single = match.groupValues[3]
            val listBody = match.groupValues[4]
            if (single.isNotEmpty()) {
                val targetBytes = unhexToBytes(single)
                if (targetBytes.isEmpty()) continue
                val base = bytesToCode(targetBytes)
                if (high - low > 65535) continue
                for (code in low..high) {
                    entries[code] = codeToString(base + (code - low))
                }
            } else if (listBody.isNotEmpty()) {
                val targets = CMapHexTokenRe.findAll(listBody).map { unhexToBytes(it.groupValues[1]) }.toList()
                for ((offset, targetBytes) in targets.withIndex()) {
                    val code = low + offset
                    if (code > high) break
                    if (targetBytes.isEmpty()) continue
                    entries[code] = String(targetBytes, CHARSET_UTF16BE)
                }
            }
        }
    }

    return ToUnicodeResult(entries, maxLength)
}

private fun extractCMapBlocks(text: String, begin: String, end: String): List<String> {
    val blocks = ArrayList<String>()
    var cursor = 0
    while (true) {
        val start = text.indexOf(begin, cursor)
        if (start < 0) break
        val finish = text.indexOf(end, start)
        if (finish < 0) break
        blocks.add(text.substring(start + begin.length, finish))
        cursor = finish + end.length
    }
    return blocks
}

private fun unhexToBytes(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    var high = -1
    for (ch in text) {
        val v = hexValue(ch.code)
        if (v < 0) continue
        if (high < 0) {
            high = v
        } else {
            out.write((high shl 4) + v)
            high = -1
        }
    }
    if (high >= 0) out.write(high shl 4)
    return out.toByteArray()
}

private fun bytesToCode(bytes: ByteArray): Int {
    var value = 0
    for (b in bytes) {
        value = (value shl 8) or (b.toInt() and 0xFF)
    }
    return value
}

private fun codeToString(value: Int): String {
    if (value < 0) return ""
    if (value < 0x10000) return value.toChar().toString()
    val adjusted = value - 0x10000
    val high = ((adjusted shr 10) + 0xD800).toChar()
    val low = ((adjusted and 0x3FF) + 0xDC00).toChar()
    return String(charArrayOf(high, low))
}

// =====================================================================================
// 矩阵与图形状态
// =====================================================================================

/** PDF 变换矩阵 [a b c d e f]。 */
private class PdfMatrix(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float
) {
    fun multiply(other: PdfMatrix): PdfMatrix = PdfMatrix(
        a * other.a + b * other.c,
        a * other.b + b * other.d,
        c * other.a + d * other.c,
        c * other.b + d * other.d,
        e * other.a + f * other.c + other.e,
        e * other.b + f * other.d + other.f
    )
}

private fun identityMatrix(): PdfMatrix = PdfMatrix(1f, 0f, 0f, 1f, 0f, 0f)

private fun translationMatrix(tx: Float, ty: Float): PdfMatrix = PdfMatrix(1f, 0f, 0f, 1f, tx, ty)

private class GraphicsState(
    val ctm: PdfMatrix,
    val fontSize: Float,
    val leading: Float,
    val charSpacing: Float,
    val wordSpacing: Float,
    val horizontalScale: Float,
    val rise: Float,
    val font: PdfObj.Dict?,
    val decoder: TextDecoder?,
    val textMatrix: PdfMatrix,
    val textLineMatrix: PdfMatrix
)

// =====================================================================================
// 内容流解释器
// =====================================================================================

private class ContentInterpreter(
    private val document: PdfDocument,
    private val output: MutableList<PdfTextItem>,
    private val segmentOutput: MutableList<PdfLineSegment>
) {
    private val stateStack = ArrayList<GraphicsState>()
    private val decoderCache = IdentityHashMap<PdfObj, TextDecoder>()

    // ===== 路径状态（用于收集表格描边线段）=====
    // 路径点一律记录为**用户空间**坐标：路径本身不属于图形状态（q/Q 不影响它），
    // 只有在描边类操作符执行的那一刻，才用当时的 CTM 变换输出，
    // 从而与文本项落在同一套坐标系里（含 Form XObject 的 /Matrix 递归）。

    /** 当前路径已构造好的候选线段，每个元素是 [x1, y1, x2, y2]（用户空间）。 */
    private val pendingSegments = ArrayList<FloatArray>()
    private var subPathStartX = 0f
    private var subPathStartY = 0f
    private var currentPointX = 0f
    private var currentPointY = 0f
    private var hasCurrentPoint = false

    private var ctm: PdfMatrix = identityMatrix()
    private var fontSize: Float = 1f
    private var leading: Float = 0f
    private var charSpacing: Float = 0f
    private var wordSpacing: Float = 0f
    private var horizontalScale: Float = 1f
    private var rise: Float = 0f
    private var textMatrix: PdfMatrix = identityMatrix()
    private var textLineMatrix: PdfMatrix = identityMatrix()
    private var currentFont: PdfObj.Dict? = null
    private var currentDecoder: TextDecoder? = null

    fun run(content: ByteArray, resources: PdfObj.Dict?, depth: Int) {
        if (depth > MAX_FORM_DEPTH) return
        val lexer = PdfLexer(content, 0)
        val operands = ArrayList<PdfObj>()
        var guard = 0
        while (!lexer.eof() && guard < 2_000_000) {
            guard++
            lexer.skipWhitespace()
            if (lexer.eof()) break
            val c = lexer.peekByte()
            val startsObject = c == 0x2F || c == 0x28 || c == 0x5B || c == 0x3C ||
                c == 0x2B || c == 0x2D || c == 0x2E || (c >= 0x30 && c <= 0x39)
            if (startsObject) {
                val before = lexer.pos
                val obj = try {
                    parsePdfObject(lexer)
                } catch (e: Exception) {
                    null
                }
                if (obj == null) {
                    if (lexer.pos == before) lexer.pos++ else Unit
                    continue
                }
                operands.add(obj)
                continue
            }
            val operator = lexer.readWord()
            if (operator.isEmpty()) {
                lexer.pos++
                continue
            }
            try {
                execute(operator, operands, resources, depth)
            } catch (e: Exception) {
                // 单条指令失败：跳过继续
            }
            operands.clear()
        }
    }

    private fun execute(operator: String, operands: List<PdfObj>, resources: PdfObj.Dict?, depth: Int) {
        when (operator) {
            "q" -> pushState()
            "Q" -> popState()
            "cm" -> {
                val matrix = matrixFromOperands(operands, operands.size - 6)
                if (matrix != null) ctm = matrix.multiply(ctm)
            }
            "BT" -> {
                textMatrix = identityMatrix()
                textLineMatrix = identityMatrix()
            }
            "ET" -> Unit
            "Tf" -> {
                if (operands.size >= 2) {
                    val name = operands[operands.size - 2].asName()
                    val size = operands[operands.size - 1].asFloat()
                    if (size != null && !size.isNaN()) fontSize = size
                    currentFont = resolveFont(resources, name)
                    currentDecoder = null
                }
            }
            "Td" -> {
                if (operands.size >= 2) {
                    val tx = operands[operands.size - 2].asFloat() ?: 0f
                    val ty = operands[operands.size - 1].asFloat() ?: 0f
                    applyTextMove(tx, ty)
                }
            }
            "TD" -> {
                if (operands.size >= 2) {
                    val tx = operands[operands.size - 2].asFloat() ?: 0f
                    val ty = operands[operands.size - 1].asFloat() ?: 0f
                    leading = -ty
                    applyTextMove(tx, ty)
                }
            }
            "Tm" -> {
                val matrix = matrixFromOperands(operands, operands.size - 6)
                if (matrix != null) {
                    textMatrix = matrix
                    textLineMatrix = matrix
                }
            }
            "T*" -> applyTextMove(0f, -leading)
            "TL" -> {
                val value = operands.lastOrNull().asFloat()
                if (value != null && !value.isNaN()) leading = value
            }
            "Tc" -> {
                val value = operands.lastOrNull().asFloat()
                if (value != null && !value.isNaN()) charSpacing = value
            }
            "Tw" -> {
                val value = operands.lastOrNull().asFloat()
                if (value != null && !value.isNaN()) wordSpacing = value
            }
            "Tz" -> {
                val value = operands.lastOrNull().asFloat()
                if (value != null && !value.isNaN()) horizontalScale = value / 100f
            }
            "Ts" -> {
                val value = operands.lastOrNull().asFloat()
                if (value != null && !value.isNaN()) rise = value
            }
            "Tj" -> showText(operands.lastOrNull().asBytes())
            "TJ" -> showTextArray(operands.lastOrNull())
            "'" -> {
                applyTextMove(0f, -leading)
                showText(operands.lastOrNull().asBytes())
            }
            "\"" -> {
                if (operands.size >= 3) {
                    wordSpacing = operands[operands.size - 3].asFloat() ?: wordSpacing
                    charSpacing = operands[operands.size - 2].asFloat() ?: charSpacing
                    applyTextMove(0f, -leading)
                    showText(operands[operands.size - 1].asBytes())
                }
            }
            "Do" -> doXObject(operands.lastOrNull().asName(), resources, depth)
            "m" -> addMoveTo(operands)
            "l" -> addLineTo(operands)
            "c" -> addCurveTo(operands, 6)
            "v" -> addCurveTo(operands, 4)
            "y" -> addCurveTo(operands, 4)
            "h" -> closeSubPath()
            "re" -> addRectangle(operands)
            "s", "S" -> {
                if (operator == "s") closeSubPath()
                flushPath(true)
            }
            "B", "B*", "b", "b*" -> {
                if (operator == "b" || operator == "b*") closeSubPath()
                flushPath(true)
            }
            "f", "F", "f*", "n" -> flushPath(false)
            else -> Unit
        }
    }

    // ===== 路径构造（只记录用户空间坐标，描边时才用当时的 CTM 变换输出）=====

    private fun addMoveTo(operands: List<PdfObj>) {
        if (operands.size < 2) return
        val x = operands[operands.size - 2].asFloat() ?: return
        val y = operands[operands.size - 1].asFloat() ?: return
        subPathStartX = x
        subPathStartY = y
        currentPointX = x
        currentPointY = y
        hasCurrentPoint = true
    }

    private fun addLineTo(operands: List<PdfObj>) {
        if (operands.size < 2) return
        val x = operands[operands.size - 2].asFloat() ?: return
        val y = operands[operands.size - 1].asFloat() ?: return
        if (hasCurrentPoint) {
            pendingSegments.add(floatArrayOf(currentPointX, currentPointY, x, y))
        } else {
            subPathStartX = x
            subPathStartY = y
        }
        currentPointX = x
        currentPointY = y
        hasCurrentPoint = true
    }

    /** 贝塞尔曲线：不输出线段，只把当前点移到终点，避免后续 `l` 的起点错乱。 */
    private fun addCurveTo(operands: List<PdfObj>, operandCount: Int) {
        if (operands.size < operandCount) return
        val x = operands[operands.size - 2].asFloat() ?: return
        val y = operands[operands.size - 1].asFloat() ?: return
        if (!hasCurrentPoint) {
            subPathStartX = x
            subPathStartY = y
        }
        currentPointX = x
        currentPointY = y
        hasCurrentPoint = true
    }

    private fun closeSubPath() {
        if (!hasCurrentPoint) return
        if (currentPointX != subPathStartX || currentPointY != subPathStartY) {
            pendingSegments.add(floatArrayOf(currentPointX, currentPointY, subPathStartX, subPathStartY))
        }
        currentPointX = subPathStartX
        currentPointY = subPathStartY
    }

    /** `re` 展开成 4 条边（成本很低，直接实现）。 */
    private fun addRectangle(operands: List<PdfObj>) {
        if (operands.size < 4) return
        val x = operands[operands.size - 4].asFloat() ?: return
        val y = operands[operands.size - 3].asFloat() ?: return
        val w = operands[operands.size - 2].asFloat() ?: return
        val h = operands[operands.size - 1].asFloat() ?: return
        pendingSegments.add(floatArrayOf(x, y, x + w, y))
        pendingSegments.add(floatArrayOf(x + w, y, x + w, y + h))
        pendingSegments.add(floatArrayOf(x + w, y + h, x, y + h))
        pendingSegments.add(floatArrayOf(x, y + h, x, y))
        subPathStartX = x
        subPathStartY = y
        currentPointX = x
        currentPointY = y
        hasCurrentPoint = true
    }

    /**
     * 结束当前路径。
     *
     * @param emit true = 描边类操作符（`S` / `s` / `B` / `B*` / `b` / `b*`），把候选线段用**当时的 CTM**
     *             变换到页面空间后输出；false = 纯填充（`f` / `F` / `f*`）或 `n`，只清空路径不输出
     */
    private fun flushPath(emit: Boolean) {
        if (emit && pendingSegments.isNotEmpty()) {
            for (segment in pendingSegments) {
                segmentOutput.add(
                    PdfLineSegment(
                        ctm.a * segment[0] + ctm.c * segment[1] + ctm.e,
                        ctm.b * segment[0] + ctm.d * segment[1] + ctm.f,
                        ctm.a * segment[2] + ctm.c * segment[3] + ctm.e,
                        ctm.b * segment[2] + ctm.d * segment[3] + ctm.f
                    )
                )
            }
        }
        pendingSegments.clear()
        hasCurrentPoint = false
    }

    private fun pushState() {
        stateStack.add(
            GraphicsState(
                ctm, fontSize, leading, charSpacing, wordSpacing, horizontalScale, rise,
                currentFont, currentDecoder, textMatrix, textLineMatrix
            )
        )
    }

    private fun popState() {
        if (stateStack.isEmpty()) return
        val state = stateStack.removeAt(stateStack.size - 1)
        ctm = state.ctm
        fontSize = state.fontSize
        leading = state.leading
        charSpacing = state.charSpacing
        wordSpacing = state.wordSpacing
        horizontalScale = state.horizontalScale
        rise = state.rise
        currentFont = state.font
        currentDecoder = state.decoder
        textMatrix = state.textMatrix
        textLineMatrix = state.textLineMatrix
    }

    private fun applyTextMove(tx: Float, ty: Float) {
        textLineMatrix = translationMatrix(tx, ty).multiply(textLineMatrix)
        textMatrix = textLineMatrix
    }

    private fun matrixFromOperands(operands: List<PdfObj>, fromIndex: Int): PdfMatrix? {
        if (fromIndex < 0) return null
        if (fromIndex + 6 > operands.size) return null
        val values = FloatArray(6)
        for (i in 0 until 6) {
            values[i] = operands[fromIndex + i].asFloat() ?: return null
        }
        return PdfMatrix(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    private fun resolveFont(resources: PdfObj.Dict?, name: String?): PdfObj.Dict? {
        if (resources == null || name == null) return null
        val fonts = document.resolve(resources.map["Font"]).asDict() ?: return null
        return document.resolve(fonts.map[name]).asDict()
    }

    private fun decoderFor(font: PdfObj.Dict?): TextDecoder? {
        if (font == null) return null
        val cached = decoderCache[font]
        if (cached != null) return cached
        val created = try {
            createTextDecoder(document, font)
        } catch (e: Exception) {
            null
        }
        if (created != null) decoderCache[font] = created
        return created
    }

    private fun showText(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val decoder = currentDecoder ?: decoderFor(currentFont).also { currentDecoder = it }
        // 有解码器就只用解码器（解不出来即丢弃，不能拿 UTF-16BE 硬猜）；
        // 完全没有字体信息时才退化为 UTF-16BE 试探
        val text = try {
            if (decoder == null) fallbackDecode(bytes) else decoder.decode(bytes)
        } catch (e: Exception) {
            null
        } ?: return
        if (text.isEmpty()) return
        val rendered = textMatrix.multiply(ctm)
        output.add(PdfTextItem(text, rendered.e, rendered.f, fontSize))
    }

    private fun fallbackDecode(bytes: ByteArray): String? {
        if (bytes.size < 2) return null
        val usable = bytes.size - (bytes.size % 2)
        if (usable <= 0) return null
        val text = String(bytes, 0, usable, CHARSET_UTF16BE)
        if (text.isEmpty() || text.contains('\uFFFD')) return null
        return text
    }

    private fun showTextArray(value: PdfObj?) {
        val items = (value as? PdfObj.Arr)?.items ?: return
        for (element in items) {
            when (element) {
                is PdfObj.Str -> showText(element.bytes)
                is PdfObj.Num -> {
                    val adjust = (-element.value / 1000.0).toFloat() * fontSize * horizontalScale
                    textMatrix = translationMatrix(adjust, 0f).multiply(textMatrix)
                }
                else -> Unit
            }
        }
    }

    private fun doXObject(name: String?, resources: PdfObj.Dict?, depth: Int) {
        if (name == null || depth >= MAX_FORM_DEPTH) return
        val xobjects = document.resolve(resources?.map?.get("XObject")).asDict() ?: return
        val xobject = document.resolve(xobjects.map[name]) as? PdfObj.Stream ?: return
        val subtype = document.resolve(xobject.dict.map["Subtype"]).asName()
        if (subtype != "Form") return

        val savedCtm = ctm
        val matrix = readFloatArray(document.resolve(xobject.dict.map["Matrix"]))
        if (matrix != null && matrix.size >= 6) {
            ctm = PdfMatrix(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]).multiply(ctm)
        }
        val formResources = document.resolve(xobject.dict.map["Resources"]).asDict() ?: resources
        val content = decodeStream(document, xobject)
        if (content != null) {
            run(content, formResources, depth + 1)
        }
        ctm = savedCtm
    }
}
