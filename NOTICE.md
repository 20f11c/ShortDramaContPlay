# 第三方代码与参考实现说明

## ad-auto

- 项目：**ad-auto**
- 地址：<https://github.com/clarithromycine/ad-auto>
- 包名：`com.wisight.adauto`（原作者实现，目标 App 与我们相同：红果免费短剧 `com.phoenix.read`）

### 我们参考了什么

本项目的无障碍检测链路（`app/src/main/java/com/shortdrama/assist/accessibility/`）是在阅读并实测
ad-auto 的实现之后重写的，以下内容直接源自该项目：

| 内容 | 落在本项目的位置 |
|---|---|
| 必须开启 `flagIncludeNotImportantViews`，否则广告 SDK 浮层在节点树不可见 | `app/src/main/res/xml/accessibility_service_config.xml` |
| 3 秒看门狗轮询（静止广告没有事件） | `DramaDetector.WATCHDOG_MS` / `startWatchdog()` |
| 遍历 `service.windows` 而非仅 `rootInActiveWindow` | `DramaDetector.collectFromAllWindows()` |
| 稀疏树兜底 + 800ms 持久性确认 | `DramaDetector.SPARSE_AD_CONFIRM_MS`、`rules/com.phoenix.read.json` 的 `sparse-fallback` |
| 倒计时窗口内不动作 | `DramaDetector.withinCountdownWindow()`、`interstitial-countdown` 规则 |
| 橙色 CTA 视觉兜底的几何判据（颜色纯度不可用作判据） | `DramaDetector` 的橙色块计算 |
| 横屏读不到文字时只点按钮、不滑动 | `DramaRules` 横屏分支、`docs/rules-schema.md` 第 6 节 |
| 前台服务保活（MIUI/HyperOS 后台挂起问题） | `accessibility/FloatingBallService.kt` |
| 拒绝 ≥60% 屏幕的超大点击目标 | `DramaRules.isOversizedClickTarget()` |

同时我们有两条**刻意不复用**的逻辑（真机上会误点）：`商城卡片点击` 与 `立即领取点击`，原因见
[README](README.md#已移除的规则真机误判来源)。

### 许可证状况

截至本项目引入这些实现时，ad-auto 仓库**没有 LICENSE 文件**，README 中也未声明许可协议，
因此按默认版权（保留所有权利）理解。

本项目据此的处理方式：

- 仅参考其**实现思路、判定优先级与经验常量**，代码为本仓库自行编写（Kotlin / Compose 结构不同、
  不含其源码文件、不引入其依赖）；
- 若后续需要直接复用其源码片段，应先向原作者确认授权方式，并在本文件中补记；
- 如原作者提出异议，可通过仓库 Issue 联系，我们会调整或移除相关实现。

## 其它

- jsDelivr（<https://www.jsdelivr.com/>）：规则包 CDN 分发。
- AndroidX / Jetpack Compose：Apache-2.0。
