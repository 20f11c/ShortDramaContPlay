# 短剧连续观看辅助工具

通过 **无障碍服务（AccessibilityService）** 直接读取红果免费短剧的界面节点树，识别片尾提示与广告浮层，自动上滑续播、跳过广告。原 **Shizuku + OCR** 链路保留为备选方案。

> 早期版本认为无障碍「读不到广告」，结论是错的：广告 SDK 会把浮层标记为「对无障碍不重要」，
> 必须在 `accessibility_service_config.xml` 里带上 `flagIncludeNotImportantViews`，
> 否则节点树里根本看不到那些视图。参考实现：[ad-auto](https://github.com/clarithromycine/ad-auto)。

## 工作方式（主方案：无障碍）

```text
AccessibilityService（AutoPlayAccessibilityService）
  ├─ TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED  事件驱动
  ├─ 看门狗：每 3 秒主动扫一次（静止广告没有事件，只能轮询）
  ├─ 遍历 service.windows（不只是 rootInActiveWindow）
  │    └─ flagIncludeNotImportantViews 让广告浮层进入节点树
  ├─ DramaRules 纯规则判定（9 级优先级）
  └─ dispatchGesture 执行上滑 / performAction 点击
```

关键配置（`app/src/main/res/xml/accessibility_service_config.xml`）：

| 配置 | 作用 |
|---|---|
| `flagIncludeNotImportantViews` | **必须**。没有它，广告 SDK 的浮层在节点树里完全不可见 |
| `flagRetrieveInteractiveWindows` | 能拿到多窗口，覆盖悬浮广告窗口 |
| `flagReportViewIds` | 用 viewId 识别穿山甲广告容器 |
| `canPerformGestures="true"` | 执行上滑手势 |
| `canTakeScreenshot="true"` | API 30+ 可用于橙色按钮视觉兜底 |

判定优先级（`DramaRules.match`）：

| 序 | 识别到 | 动作 |
|---|---|---|
| 1 | 片尾提示（待续播 / 下一集 / 精彩继续 …），且不在列表页 | 上滑续播 |
| 2 | 正剧保护（倍速 / 选集 / 第 X 集 …） | **不动**，直接返回 |
| 3 | 直播广告渲染视图 `ttlive_player_render_view` | 上滑 |
| 4 | TextureView 试玩广告（整页无文案） | 上滑 |
| 5 | 穿山甲广告容器 ID（`ay0` + `i0t`） | 上滑 |
| 6 | 「立即领取」/ 商城卡片 | 点击（拒绝 ≥60% 屏幕的超大区域） |
| 7 | 右上角「广告」角标 | 上滑 |
| 8 | 跳过 / 关闭（仅接受小面积按钮） | 点击 |
| 9 | 稀疏树兜底 | 上滑（需 800ms 持久性确认） |

安全约束：

- **超大点击目标一律不点**：面积 ≥ 屏幕 60% 的可点击节点通常是整页广告创意容器，点下去会进落地页或直播间。
- **正剧保护优先于广告动作**：页面出现播放控件或集数文案时，一个广告动作都不执行。
- **片尾排除列表页**：首页有「继续观看」栏目名、详情页有「下一集」按钮，靠列表特征词排除。
- **倒计时期间不划**：`3s` 之类的倒计时窗口内等待，避免把倒计时当成可跳过的广告。
- **未知页面不动**。

## 备选方案（Shizuku + OCR）

```text
Shizuku → screencap / dumpsys window / input swipe
        ↑
OcrAutomationService（约 1s 一轮）→ ML Kit 中文 OCR → OcrDecider
```

无障碍不可用（系统限制、厂商拦截）时可退回这条路。设置页「OCR 模式（备选）」。

## 已验证的结论

| 项 | 结果 |
|---|---|
| 片尾识别 + 自动续播 | ✅ 真机实测「第145集 → 第146集」自动推进 |
| 插屏广告倒计时 | ✅ 读到「8秒后可继续上滑观看短剧」后按倒计时推迟，结束后一次性上滑 |
| 无障碍能否读到广告节点 | ✅ 前提是带 `flagIncludeNotImportantViews` |
| 静止广告不触发事件 | ✅ 靠 3 秒看门狗轮询兜住 |
| 首页信息流不误判 | ✅ 连续上滑 6 屏零动作（早前会误点底部「商城」标签，已删除该规则） |
| 横屏全屏播放器不误判 | ✅ 横屏只允许点「跳过/关闭」，不做任何滑动 |
| 后台保活 | 前台服务 + ColorOS/MIUI 需允许「后台运行」，否则约 10–20 秒收不到事件 |
| 颜色纯度不能作判据 | 同一橙色按钮 `adb screencap` 纯度 0.998，App 内 `takeScreenshot` 仅 0.68（Display P3 vs sRGB），只能用几何判据 |
| 全屏/小窗 | 小窗可能被误识别，测试时先关画中画 |

### 已移除的规则（真机误判来源）

ad-auto 里有两条「金币/商城」动作，对续播工具是纯噪音并且会真的点错地方，已删除：

- **商城卡片点击**：首页底部导航本来就有一个「商城」文字，规则把标签当广告卡片点了，App 自己跳进商城页。
- **立即领取点击**：那是签到/金币提现漏斗，不是跳过广告。

跳过广告现在只保留三种安全动作：上滑（广告页）、点「跳过/关闭/知道了」（小按钮）、横屏只点按钮不滑动。

### 调试注意：`uiautomator dump` 会踢掉无障碍服务

`adb shell uiautomator dump` 走的是 UiAutomation，它会抢占无障碍连接，导致我们的服务被销毁再重连
（logcat 里能看到 `uiautomator Launcher` 启动的同一时刻出现 `无障碍服务已断开`）。所以：

- 不要用 uiautomator 观察本 App 自己的连接状态，会得到「未连接」的假象；
- 同理 `adb shell am force-stop <包名>` 会**把无障碍服务从系统设置里摘掉**，必须重新写入 `enabled_accessibility_services` 才能恢复；
- 调试状态请看 logcat（`SDA11y` / `DramaDetector`），不要看 dump。

## 使用前提

- Android 7.0+（`minSdk 24`）；截图视觉兜底需 Android 11+。
- 在系统设置 → 无障碍 → 「续播」中开启服务。
- 建议把 App 设为「不优化电池 / 允许后台运行」，否则服务会被冻结。
- 仅支持红果免费短剧 `com.phoenix.read`。
- 备选 OCR 模式另需安装并授权 [Shizuku](https://shizuku.rikka.app/)；手机重启后需重新启动 Shizuku。

## 目录结构

```text
docs/      开发文档（入口：docs/00-索引.md）
app/       Android 客户端（Kotlin，AGP 8.13 / compileSdk 36）
tools/     adb 压测脚本与 UI 原型
evidence/  实测原始数据
```

当前实现集中在：

- `app/src/main/java/com/shortdrama/assist/accessibility/`：主方案。`AutoPlayAccessibilityService`（服务入口）、`DramaDetector`（检测引擎 + 看门狗 + 手势）、`DramaRules`（规则引擎）、`FloatingBallService`（前台保活）、`A11yState`（状态共享）。
- `app/src/main/java/com/shortdrama/assist/ocr/`：备选方案。Shizuku UserService、截图、OCR、判定、前台服务。
- `app/src/main/java/com/shortdrama/assist/ui/settings/A11yScreen.kt`：无障碍授权/开关/状态页。
- `app/src/main/java/com/shortdrama/assist/ui/settings/OcrScreen.kt`：备选 OCR 模式的授权/开关页。
- `app/src/main/java/com/shortdrama/assist/DevLog.kt`：开发者模式日志（内存环形缓冲，可选开启）。

## 构建与安装

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
gradle :app:assembleDebug
adb install -r -i com.android.vending app\build\outputs\apk\debug\app-debug.apk
```

> JDK 必须是 21。默认的 JDK 25 会让 Kotlin 2.2.0 在 `JavaVersion.parse` 崩溃（`What went wrong: 25.0.3`）。

当前版本 `1.4.1`（versionCode 16）。调试版包名带 `.debug`，可与正式版共存。

调试日志：

```bash
adb logcat -s SDA11y:V DramaDetector:V SDFloatingBall:V
```

## 开发者模式

设置 → 其它 → 开发者模式，可开启 App 内日志记录：

- 只写入本机内存，最多 800 条，App 进程退出后清空；logcat 仍始终输出。
- 统一格式 `[分类] 中文描述`（如 `[a11y] 片尾待续播`）；同一判定只记一次，动作结果每次记录。
- 支持一键复制、系统分享、清空；日志默认关闭，需手动开启。
- 调试版也可直达：`adb shell am start -n com.shortdrama.assist.debug/com.shortdrama.assist.MainActivity --es screen devlog`。

## 免责声明

本工具仅供个人自用，仅在本机进行 UI 自动化，不访问第三方 App 的服务端接口。自动化操作可能违反第三方用户协议；请勿用于售卖、代充或商业分发。
