package com.shortdrama.assist

/**
 * 授权状态门面。
 *
 * 商业化与后端是**延后但不取消**（见 docs/03、docs/04）。自动化层只通过这里问一句
 * "现在有权吗"，将来接上服务端签名校验时**只需替换本对象的实现**，
 * UI 层与自动化层都不用改 —— 这正是当初分层的意义。
 *
 * 当前阶段（个人使用）：本地实现，恒为已授权、不限时长。
 */
object AuthStateHolder {

    /** 是否已授权。自动化层每次动作前都会问这个。 */
    @Volatile
    var entitled: Boolean = true

    /** 剩余天数。-1 表示不限（当前阶段）。 */
    @Volatile
    var remainingDays: Int = -1

    /** 环形进度 0..1。-1 表示不限（此时环形画满）。 */
    @Volatile
    var remainingFraction: Float = -1f

    /** 副文案，例如"上次校验 2 分钟前"。 */
    @Volatile
    var verifyLabel: String = "个人使用 · 不校验授权"

    /** 需要用户处理时的提示。 */
    @Volatile
    var actionHint: String = ""
}
