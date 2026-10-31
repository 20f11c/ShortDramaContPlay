package com.shortdrama.assist.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * ML Kit 中文 OCR。
 *
 * 只保留 **行级** 结果：行框比整块/单字更稳，点“跳过”时也不会点到半个字。
 * 识别器是线程安全的，创建一次反复用。
 */
class OcrEngine {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    )

    fun recognize(bitmap: Bitmap): OcrSnapshot {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = Tasks.await(recognizer.process(image), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val lines = ArrayList<OcrLine>(result.textBlocks.size * 2)
        val full = StringBuilder()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (line.text.isBlank()) continue
                lines += OcrLine(
                    text = line.text,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                )
                if (full.isNotEmpty()) full.append(' ')
                full.append(line.text)
            }
        }
        return OcrSnapshot(lines = lines, fullText = full.toString())
    }

    fun close() {
        runCatching { recognizer.close() }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 4L
    }
}
