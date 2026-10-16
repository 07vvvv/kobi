package com.xixi.timetable.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 本地离线中文 OCR：把一张课表图片识别成**带坐标的文字行**。
 *
 * 依赖 `com.google.mlkit:text-recognition-chinese:16.0.1`（bundled 内置中文模型，不联网、不依赖 GMS）。
 *
 * 为什么必须返回坐标而不是纯文本：课表截图里的「星期」在表头、「节次」在最左列、
 * 课程文字在网格内部，三者物理上不在同一行。把 textBlocks 拍平成纯文本后再按行解析，
 * 永远得不到「某门课在星期几的第几节」——这正是旧实现识别结果恒为 0 条的根因。
 *
 * 识别前会按最长边做放大预处理（见 [scaleFor]）：教务系统截图的单行字高常小于 12px，
 * 不放大时 ML Kit 的中文识别率显著劣化。
 *
 * 对外只暴露一个挂起函数；ML Kit 的 `Task` 与 `TextRecognizer` 都封在本文件内，不泄漏给调用方。
 * 解码失败、识别失败、图中无文字一律返回空列表，绝不抛异常。
 */
object ImageTextRecognizer {

    /** 放大目标：把最长边推到该尺寸附近（对应内存上界约 2560×1920×4 ≈ 20MB） */
    private const val TARGET_LONGEST_SIDE = 2560

    /** 放大倍数上限，避免极小缩略图被放大到无法承受的尺寸（3× 时输出约 2400px ≈ 17MB） */
    private const val MAX_SCALE = 3.0f

    /**
     * 识别 [uri] 指向的图片，返回按「先 top 后 left」升序排好的文字行。
     *
     * @return 识别出的文字行；失败、无文字或无法解码时返回空列表。
     */
    suspend fun recognize(context: Context, uri: Uri): List<OcrLine> {
        // 1) 取识别器：bundled 中文模型，首次调用会装载内置模型，失败即返回空列表。
        val recognizer = try {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        } catch (t: Throwable) {
            return emptyList()
        }

        var sourceBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        return try {
            // 2) 解码 + 放大（CPU 与内存密集，切到 IO 线程）。
            //    返回 Pair(原图, 放大图)；未放大时第二项为 null，无法解码时两项都为 null。
            val decoded = withContext(Dispatchers.IO) { decodeAndScale(context, uri) }
            sourceBitmap = decoded.first
            scaledBitmap = decoded.second

            // 放大成功就用放大图；1.0× 时没有放大图，直接送原图；都为空说明解码失败。
            val target = scaledBitmap ?: sourceBitmap ?: return emptyList()

            // 3) 用缩放后的位图构造 InputImage，此后不再做任何重缩放。
            val image = InputImage.fromBitmap(target, 0)
            val lines = recognizeLines(recognizer, image)

            // 4) 先 top 后 left 升序：网格重建依赖这个顺序做行内切块与纵向对齐。
            lines.sortedWith(compareBy({ it.top }, { it.left }))
        } catch (t: Throwable) {
            emptyList()
        } finally {
            // 5) 释放位图与识别器。recognizeLines 返回时 ML Kit 的 Task 已结束，
            //    因此此处回收位图是安全的；recycle/close 自身失败不影响返回值。
            runCatching { scaledBitmap?.recycle() }
            runCatching { sourceBitmap?.recycle() }
            runCatching { recognizer.close() }
        }
    }

    /**
     * 把 ML Kit 的 `Task<Text>` 桥接成挂起调用，并在此完成「textBlocks → lines → OcrLine」的取值。
     *
     * 未使用 `kotlinx-coroutines-play-services` 的 `await()`（本项目未声明该依赖），
     * 因此用 `suspendCancellableCoroutine` + `addOnSuccessListener` / `addOnFailureListener` 手工桥接。
     * ML Kit 的 Task 默认在主线程回调，回调里 resume 协程是线程安全的。
     *
     * 取值口径：只用 `Text.textBlocks → block.lines → line.text + line.boundingBox`，
     * **不**使用 `Text.text`（那会丢掉坐标）。`boundingBox` 为 null、或文本为空白的行直接跳过。
     */
    private suspend fun recognizeLines(
        recognizer: TextRecognizer,
        image: InputImage
    ): List<OcrLine> = suspendCancellableCoroutine { continuation ->
        val task = recognizer.process(image)
        task.addOnSuccessListener { recognized ->
            if (continuation.isActive) {
                val lines = ArrayList<OcrLine>()
                for (block in recognized.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        if (line.text.isBlank()) continue
                        lines.add(OcrLine(line.text, box.left, box.top, box.right, box.bottom))
                    }
                }
                continuation.resume(lines)
            }
        }
        task.addOnFailureListener { error ->
            if (continuation.isActive) {
                continuation.resumeWithException(error)
            }
        }
    }

    /**
     * 先只读图片尺寸（`inJustDecodeBounds`，不分配像素内存），再按最长边决定倍数解码。
     *
     * 采用「一次性读入字节数组 + decodeByteArray」而不是两次 decodeStream：
     * content:// 的输入流重复打开的成本与行为不一致，字节数组方式更可控。
     *
     * @return `Pair(原图, 放大图)`；未放大时第二项为 null，无法解码时两项都为 null。
     */
    private fun decodeAndScale(context: Context, uri: Uri): Pair<Bitmap?, Bitmap?> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null to null
        if (bytes.isEmpty()) return null to null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null to null

        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null to null

        val scale = scaleFor(max(width, height))
        // 1.0× 表示原图已经够大，不做任何放大，省掉一次整幅拷贝。
        if (scale <= 1f) return source to null

        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }.getOrNull()

        // createScaledBitmap 在目标尺寸与原尺寸相同时会直接返回原图，
        // 这里显式排除，保证「原图」与「放大图」始终是两个不同对象（便于分别回收）。
        if (scaled == null || scaled === source) return source to null
        return source to scaled
    }

    /**
     * 按最长边决定放大倍数，把最长边**直接推到** [TARGET_LONGEST_SIDE] 附近。
     *
     * 之所以必须放大：教务系统导出的课表截图单行字高常只有 10px 上下，
     * 而 ML Kit 的中文识别在字高约 20px 时才稳定，不放大时识别率显著劣化。
     *
     * 这里用「目标尺寸」而不是「分档倍数」：分档会让 1799px 的图被放大到 3598px（约 57MB），
     * 而目标尺寸法把内存上界稳定在约 20MB，同时保证 1280px 的典型样张一定被放大到 2560px
     * （约 2.0×，字高 10px → 20px，正好落进可用区间）。
     */
    private fun scaleFor(longestSide: Int): Float {
        if (longestSide <= 0) return 1.0f
        if (longestSide >= TARGET_LONGEST_SIDE) return 1.0f
        val factor = TARGET_LONGEST_SIDE.toFloat() / longestSide
        return factor.coerceIn(1.0f, MAX_SCALE)
    }
}
