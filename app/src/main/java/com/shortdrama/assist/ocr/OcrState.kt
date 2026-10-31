package com.shortdrama.assist.ocr

/** 跨 Compose / 服务共享的只读运行状态。 */
object OcrState {
    @Volatile var running: Boolean = false
    @Volatile var status: String = "未启动"
    @Volatile var lastText: String = ""
    @Volatile var lastAction: String = ""
    @Volatile var lastLatencyMs: Long = 0L
    @Volatile var foregroundPackage: String = ""
}
