package com.xixi.timetable.data.ocr

import android.content.Context
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

/**
 * 本地离线中文 OCR：把一张课表图片识别成多行文本。
 *
 * 依赖 `com.google.mlkit:text-recognition-chinese:16.0.1`（bundled 内置中文模型，不联网、不依赖 GMS）。
 *
 * 对外只暴露一个挂起函数；ML Kit 的 `Task` 与 `TextRecognizer` 都封在本文件内，不泄漏给调用方。
 * 任何失败（无法解码、识别器不可用、模型报错、图中无文字）都返回空字符串，绝不抛异常。
 */
object ImageTextRecognizer {

    /**
     * 识别 [uri] 指向的图片，返回逐行拼接的文本（行间 `"\n"`）。
     *
     * @return 识别出的文本；失败、无文字或无法解码时返回空字符串 `""`。
     */
    suspend fun recognize(context: Context, uri: Uri): String {
        // 1) 取识别器：bundled 中文模型，首次调用会装载内置模型，失败即返回空串。
        val recognizer = try {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        } catch (t: Throwable) {
            return ""
        }

        return try {
            // 2) 解码图片：读文件 + 解码，切到 IO 线程，避免阻塞主线程。
            val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
            // 3) 识别：把 Task 桥接成挂起调用（成功/失败都在此收口）。
            val raw = recognizeText(recognizer, image)
            // 4) 规范化：逐行 trim、丢弃空行、用 "\n" 连接。
            normalize(raw)
        } catch (t: Throwable) {
            ""
        } finally {
            // 5) 每次都释放原生资源；close 自身失败不影响返回值。
            runCatching { recognizer.close() }
        }
    }

    /**
     * 把 ML Kit 的 `Task<Text>` 桥接成挂起调用。
     *
     * 未使用 `kotlinx-coroutines-play-services` 的 `await()`（本项目未声明该依赖），
     * 因此用 `suspendCancellableCoroutine` + `addOnSuccessListener` / `addOnFailureListener` 手工桥接。
     * ML Kit 的 Task 默认在主线程回调，回调里 resume 协程是线程安全的。
     */
    private suspend fun recognizeText(recognizer: TextRecognizer, image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            val task = recognizer.process(image)
            task.addOnSuccessListener { recognized ->
                if (continuation.isActive) {
                    continuation.resume(recognized.text)
                }
            }
            task.addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
        }

    /** 逐行 trim、丢弃空行，再用 `"\n"` 连接；没有任何有效行时返回空字符串。 */
    private fun normalize(raw: String): String =
        raw.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
}
