# 规则包标准（schemaVersion 1）

这个文件是**唯一**的规则格式说明。官方源和第三方源用同一套格式，没有任何私有扩展。

- 官方源：`rules/com.phoenix.read.json`（就在本仓库里，随仓库更新）
- 第三方源：任何人自己的 HTTPS 地址，返回同样结构的 JSON

**文件命名约定**：一个规则包只服务一个 App，文件名就用目标 App 的包名 —— `rules/<包名>.json`
（例：`rules/com.phoenix.read.json`）。这样看文件名就知道适配谁，多个 App 放同一目录也不会撞名；
改名后记得同步改订阅里的地址（App 内「订阅详情 → 编辑订阅地址」）。

---

## 1. 设计前提

规则包是**纯数据**。App 只读它、校验它、据它匹配界面节点，**永不执行其中任何内容**。

因此规则包里没有、也不允许出现：

- 表达式、脚本、宏、模板替换
- 回调 URL、webhook、上报地址
- 任何形式的 `eval` / `exec` / `Function` 语义

规则能表达的只有三件事：**在什么条件下、对什么节点、做什么动作**。

---

## 2. 顶层结构

```json
{
  "schemaVersion": 1,
  "name": "红果免费短剧",
  "package": "com.phoenix.read",
  "version": "2026.10.31",
  "minAppVersion": "1.4.0",
  "homepage": "https://github.com/20f11c/ShortDramaContPlay",
  "rules": []
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `schemaVersion` | 是 | 当前固定为 `1`。高于 App 支持的版本 → 整包忽略 |
| `name` | 是 | 订阅显示名，建议 ≤ 12 字 |
| `package` | 是 | 目标 App 包名。**规则只在这个包名位于前台时生效** |
| `version` | 是 | 规则包版本，建议 `YYYY.MM.DD` 或语义化版本 |
| `minAppVersion` | 否 | 需要的最低 App 版本；当前 App 低于它 → 整包忽略，继续用旧缓存 |
| `homepage` | 否 | 作者主页，仅展示 |
| `rules` | 是 | 规则数组，**上限 200 条** |

单个文件 **≤ 256 KB**，超过直接拒收。

---

## 3. 规则通用字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 包内唯一，用于日志与排错 |
| `type` | 是 | 判据类型，见第 4 节 |
| `anchor` | 是 | 该类型的匹配条件 |
| `action` | 是 | 命中后做什么，见第 5 节 |
| `immediate` | 否 | `true` = 不受动作冷却限制（片尾续播用） |
| `cooldownMs` | 否 | 该条规则两次触发的最小间隔，默认 1500 |
| `confirmMs` | 否 | 需要条件持续成立多久才算命中，默认 0 |
| `note` | 否 | 人类可读说明，App 不解析 |

规则**按数组顺序**求值，第一条命中即停止。所以把「最确定」的规则放前面。

---

## 4. 判据类型（`type`）

### 4.1 `pageText` — 整页文案

```json
{"type": "pageText", "anchor": {"any": ["上滑继续观看", "待续播"]}, "action": "swipeUp"}
{"type": "pageText", "anchor": {"regex": ["\\d+\\s*秒后可继续"]}, "action": "wait"}
```

`anchor` 支持 `any`（包含任一）与 `regex`（任一正则命中），二者只能选一个。文案来源是页面上所有可见节点的 `text` + `contentDescription` 拼接。

### 4.2 `viewId` — 资源 ID

```json
{"type": "viewId", "anchor": {"any": ["ttlive_player_render_view"]}, "action": "swipeUp"}
```

匹配节点的 `viewIdResourceName`。这是最稳的判据，但**随目标 App 版本变化**，需要维护。

### 4.3 `className` — 类名

```json
{"type": "className", "anchor": {"contains": ["TextureView"], "maxPageTextLen": 24}, "action": "swipeUp"}
```

`contains` 是类名子串匹配。`maxPageTextLen` 是可选的附加约束：整页文案长度上限。**建议总是带上**——正剧播放器同样含 `TextureView`，只有广告页才几乎没有文案。

### 4.4 `clickText` — 可点击文案

```json
{"type": "clickText", "anchor": {"any": ["跳过", "关闭"], "maxWidthRatio": 0.28, "maxHeightRatio": 0.10}, "action": "click"}
```

在文案命中、且节点**自身或祖先**可点击时，点击该节点。`maxWidthRatio` / `maxHeightRatio` 是相对屏幕的尺寸上限，用来排除「整页创意容器」——这类节点点下去会进落地页或直播间。

### 4.5 `sparse` — 稀疏树兜底

```json
{"type": "sparse", "anchor": {"maxNodes": 50, "maxPageTextLen": 20}, "action": "swipeUp", "confirmMs": 800}
```

节点数少、文案几乎没有、且没有播放控件时成立。这条**必须是最后一条**，它就是兜底。`confirmMs` 强烈建议设为 600–1000：广告切正剧的过渡帧只持续约 400ms，不确认会误动作。

### 4.6 `adBadge` — 广告角标

```json
{"type": "adBadge", "anchor": {"any": ["广告"], "region": "topRight"}, "action": "none"}
```

识别用，通常配 `action: "none"` 只做日志。`region` 可选 `topRight` / `topLeft` / `any`。

---

## 5. 动作（`action`）

| 值 | 含义 |
|---|---|
| `swipeUp` | 上滑一屏（网剧类广告页的标准划走动作） |
| `swipeDown` | 下滑一屏 |
| `click` | 点击命中的节点 |
| `back` | 系统返回 |
| `wait` | 明确**不动作**（用于倒计时等待，会记录日志） |
| `none` | 识别但不动作（仅日志） |

---

## 6. 引擎护栏：规则改不动

下面这些**写在 App 里，与规则包无关，任何规则都无法绕过**：

1. **包名闸门** —— 只在订阅声明的 `package` 是前台时匹配
2. **正剧保护** —— 页面出现「倍速 / 选集 / 第X集 / 弹幕 / 进度条」时，所有滑动动作一律拒绝
3. **超大目标拒绝** —— 面积 ≥ 屏幕 60% 的可点击节点不点
4. **倒计时期间不划** —— 页面出现「N 秒后可继续」时推迟动作
5. **横屏只点按钮** —— 横屏下不允许任何滑动，只允许点 `clickText` 命中的小按钮
6. **触发频率上限** —— 单个订阅 ≤ 1 次 / 1.5s、≤ 30 次 / 分钟，超限自动暂停该订阅并在详情页标红
7. **过渡帧确认** —— 声明了 `confirmMs` 的规则必须条件持续成立才动作

换句话说：**规则可以订阅，动作和护栏不可订阅。** 一份写得再激进的规则包，也做不到「在正剧页两秒滑一次」。

---

## 7. 发布自己的规则源

1. 写一个 JSON 文件，放到任意 HTTPS 可访问的位置
2. 结构照本文档；`package` 填目标 App 包名
3. 在 App 里「订阅 → ＋」填入该 URL
4. 可以加签名，也可以不加 —— **签名是可选的来源标识，不是门禁**

关于签名：官方源附 `signature` 字段并用内置公钥可验证，用来证明「这是官方发的」。第三方源不需要签名，App 也不会因为没有签名而拒收。信任关系是「用户信任他填的那个 URL」。

---

## 8. 兼容与失败处理

| 情况 | 行为 |
|---|---|
| 拉取失败（网络 / 404 / 超时） | 保留上一次成功缓存，继续用；详情页标红「更新失败 · 沿用缓存」 |
| `schemaVersion` 不支持 | 拒收该包，保留旧缓存 |
| `minAppVersion` 高于当前 App | 拒收该包，保留旧缓存（旧版 App 不会被新规则带崩） |
| 单条规则校验失败 | 丢弃该条，其余规则正常生效，日志记录被丢弃的 `id` |
| 包内规则数或体积超限 | 拒收该包 |

任何一次失败都不会让 App 停止工作 —— 最坏情况是沿用上一次的规则。

---

## 9. 版本

- `schemaVersion: 1` —— 本文档
- 新增判据类型 → `schemaVersion` 升为 2，且 App 必须同时支持 1 和 2
- 仅新增可选字段 → 不升版本，旧 App 忽略未知字段即可
