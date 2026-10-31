package com.shortdrama.assist.accessibility

/** 跨 Compose / 无障碍服务共享的只读运行状态。 */
object A11yState {
    /** 服务是否已连接（用户已开启无障碍） */
    @Volatile var connected: Boolean = false

    /** 最近一次检测结果（一句话） */
    @Volatile var status: String = "未启动"

    /** 最近一次实际执行的动作描述，如 "片尾待续播" */
    @Volatile var lastAction: String = ""

    /** 最近一次动作时间戳（uptimeMillis） */
    @Volatile var lastActionAt: Long = 0L

    /** 最近一次观测到的前台包名 */
    @Volatile var foregroundPackage: String = ""

    /** 最近一次观测到的页面文本（截断后用于调试） */
    @Volatile var lastText: String = ""

    /** 最近一次检测的节点数量，便于判断节点树是否稀疏 */
    @Volatile var lastNodeCount: Int = 0
}
