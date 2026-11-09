# 论坛助手 · Android

一个专注「掌上阅读论坛」的 Android 客户端，参考 iOS 端《论坛助手》的产品思路重新实现：
把多个 Discuz / PHPWind / XenForo 论坛聚合成一条时间线，用原生页面流畅地读帖、回帖、收藏与离线保存。

> 这是个人学习 / 自用项目，与《论坛助手》官方无关。所有内容版权归各论坛及原作者所有。

---

## 1. 已实现功能

| 模块 | 说明 |
| --- | --- |
| 多站点聚合 | 首页把已启用站点的「最新主题 / 最新回复 / 热帖 / 精华」合并成一条信息流，并发抓取、自动去重，按时间倒序 |
| 站点管理 | 粘贴网址自动识别站点类型（Discuz / PHPWind / XenForo / Vbulletin），支持启用、删除、排序色标 |
| 站点画像 | 单站差异（编码、四格缺字段、标题截断、跳转链）收在 `SiteProfile` 开关里，新站适配不影响已有站点 |
| 首页四格 | 一次请求解析首页四格，四栏（最新主题 / 最新回复 / 热帖 / 精华）同时拿到，条目与站点桌面版一致 |
| 版块浏览 | 自动解析版块列表 → 主题列表，支持最新回复 / 最新发表 / 精华排序与分页加载 |
| 帖子阅读 | 楼层卡片、楼主标识、头像、引用块、图片块、长按可选文本；自动记录阅读位置并显示「+N 新回复」 |
| 发帖 / 回复 | Discuz 表单自动取 formhash 提交，支持标题、正文与表情快捷插入 |
| 登录 | Discuz 账号密码登录，Cookie 持久化在本机（不上传任何服务器） |
| 收藏 / 离线 / 历史 | 本地 JSON 存储，无网可读；支持一键离线整帖 |
| 搜索 | 首页跨站搜索；从「论坛 → 站点」进入时只搜该站点 |
| 内容过滤 | 屏蔽关键词 / 作者，信息流即刻变干净 |
| 个性化 | 深色模式、6 套主题色、正文字号缩放（85%–140%） |
| 网页兜底 | 任意站点可切「网页模式」，内嵌 WebView 直接浏览原站，抓取失败也不会卡死 |
| 板块快捷 | 在「论坛 → 站点 → 版块」点 ☆ 固定版块，底栏「板块」一键直达 |

## 2. 技术栈

- Kotlin + Jetpack Compose（Material 3）+ Navigation Compose
- OkHttp + Jsoup：网络与 HTML 解析
- Coil：图片加载
- 原生 SharedPreferences / 文件存储：站点、Cookie、收藏、离线、设置
- minSdk 24 / targetSdk 34 / AGP 8.2.2 / Gradle 8.7 / JDK 17

## 3. 目录结构

```
app/src/main/java/com/forumassist/app/
├── MainActivity.kt          # 入口
├── App.kt                   # 全局单例
├── AppState.kt              # 状态中心：信息流、站点、收藏、离线、设置
├── data/
│   ├── Models.kt            # Site / Board / ThreadSummary / Post / Block
│   ├── Store.kt             # 本地持久化（含 Cookie）
│   ├── Http.kt              # OkHttp + 持久化 CookieJar + 站点探测
│   ├── ForumClient.kt       # 各页面抓取与解析、登录、发帖
│   ├── HtmlBlocks.kt        # HTML → 文本/图片/引用块
│   ├── Attachments.kt       # 附件解析（标准 tattl / 附件美化插件 / 图片附件）
│   ├── SiteProfile.kt       # 站点画像：单站差异开关（预设站点隔离）
│   ├── SiteAdaptation.kt    # 通用站点适配规则（新添加站点自动探测）
│   ├── SignSupport.kt       # 每日签到（sx_signin / k_misign / dsu_paulsign / 登录奖励）
│   ├── TimeUtil.kt          # "3 小时前" / "昨天" 解析
│   └── Demo.kt              # 演示站点数据
└── ui/
    ├── theme/Theme.kt       # 配色、圆角、字号
    ├── components/Common.kt # 帖子卡片、空状态、设置行等
    ├── AppRoot.kt           # 导航与底部栏
    └── screens/             # 首页 / 论坛 / 板块 / 主题列表 / 详情 / 编辑器 / 登录 / 搜索 / 设置 / 收藏 / 我的 / 网页
```

## 4. 编译

```bash
# 本地需要 JDK 17 与 Android SDK（platform 34 / build-tools 34.0.0）
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开根目录（Ladybug 及以上版本），等待 Gradle 同步完成后点 Run。

仓库中已附带签名过的 `app-release.apk`（debug 签名，仅供自用安装）：

```bash
adb install -r app-release.apk
```

## 5. 使用说明

1. 首次打开预置「尚香书苑」「搜书吧」「老王论坛」三个站点：域名变了会从地址发布页自动纠正（搜书吧的发布页是三级跳，App 会跟完整条链路再挑「最新地址」）。
2. 「论坛」页点右上角「添加」，粘贴任意 Discuz 论坛首页地址即可，App 会按「通用适配规则」自动探测站点特征（引擎、信息流来源、签到插件、登录墙），并把结果应用到该站点。
3. 每日签到：站点列表里每个站点有「签到」按钮（已签变灰）。支持 sx_signin（尚香自研，带验证码）、k_misign（迷神签到）、dsu_paulsign（DSU 心情签到）；搜书吧这类没有签到插件的站点自动按「每日登录奖励」处理。
3. 阅读：首页卡片 → 帖子详情；右上角可收藏 / 离线 / 复制链接 / 分享 / 网页打开。
4. 发帖与回复：需要先在该站点「登录」。部分站点开启验证码或安全提问，这类账号请用网页模式登录。
5. 抓不到内容时，优先切「网页模式」——内置 WebView 与站点共享 Cookie，已登录状态可直接发帖。
6. 小说站的正文常常整个放在附件里（帖子只有一句简介），点附件 →「在线阅读」即可直接看 txt，不用先下载再找文件管理器；GBK 编码的旧文本会自动识别。

## 6. 站点适配规则（通用适配）

新添加的站点不再需要逐个手工适配：App 会按 `SiteAdaptation.kt` 里的一组**通用规则**自动探测，
探测结果写入站点记录并立即生效；个别站点有特殊差异时，再在 `SiteProfile.kt` 里开独立开关（站点之间完全隔离，改一个不影响其他）。

| 规则 | 内容 | 实现位置 |
| --- | --- | --- |
| R1 引擎识别 | discuz / forum.php / viewthread → Discuz；phpwind / xenforo / vbulletin 同理 | `SiteDetector.detectType` |
| R2 编码自适应 | 响应头 charset → meta charset → 主机历史编码；GBK / UTF-8 均免配置，中文提交也按站点编码 | `Http.decode` / `Http.post` |
| R3 信息流来源 | 首页有门户四格（subjectbox / replaybox / hottiebox / goodtiebox 或「最新主题/最新回复/热帖/精华」标题）→ 走四格；否则自动回落「导读」（mod=guide 四个 view），四格站点解析失败还会熔断 10 分钟防空转 | `ForumClient.portalBundle` + `AppState.loadFeed` |
| R4 详情页结构 | 桌面版 `div[id^=post_]` + `td.t_f` 为标准正文；详情入口按「原始链接 → page=N → 伪静态 → mobile=no → archiver」顺序探测 | `ForumClient.threadPage` |
| R5 签到插件探测 | 首页特征串识别签到插件：`sx_signin`（验证码+JSON 接口）、`k_misign`（GET qiandao&formhash）、`dsu_paulsign`（心情表单，通用解析 `<form>` 默认值提交）；没有插件则不显示签到按钮 | `SiteAdaptation.analyze` + `SignSupport` |
| R6 登录墙 | 首页即登录表单（loginsubmit / 「没有账号」）→ 添加成功后提示先登录 | `SiteAdaptation.analyze` |
| R7 图片防盗链 | 正文图片默认带帖子页 Referer（与浏览器一致）；带 Referer 失败自动去 Referer 重试，两个方向都有退路 | `PostImage` |
| R8 占位图防御 | 加载成功但尺寸 ≤48px 的小图（loading.gif / 文件类型图标）判为占位图自动换候选；全部候选都是小图则整个图块不渲染 | `PostImage` / `Attachments.legacy` |

**适配优先级**：站点级探测值（`Site.signKind`，添加时写入并持久化）＞ 预设站点内置画像（`SiteProfile.Shangxiang/Soushu/Laowang`）＞ 通用默认（`SiteProfile.Default`）。
预设站点的名称/域名匹配规则见 `SiteProfile.of()`；用户新加的站点一律先走通用规则，之后如果某个站点解析不对，把现象反馈后在画像里给它单独加开关，不影响其他站点。

**验证过的参考样本**：尚香书苑（sx_signin 验证码协议）、搜书吧（无签到插件，登录奖励 +2 银币）、老王论坛（k_misign，登录态按钮 href 实测）——三个站点的差异点全部按站点隔离实现。

## 7. 已知限制

- 各论坛模板差异极大，解析器采用多套选择器降级，仍可能对个别站点失效（此时会自动回落到网页模式提示）。
- 验证码 / 滑块 / 安全提问类登录无法在原生表单完成。
- 暂不支持本地相册上传图片（可先上传图床再粘贴链接）。
- 只读接口遵循各站点公开页面，请控制刷新频率，遵守版规与 robots 协议。

## 8. 后续可做

- 图片上传、草稿箱、楼层定位、只看楼主、子帖提取
- 多账号切换
- 更多签到插件适配（按 R5 规则扩展特征表即可）
- AI 辅助回帖（DeepSeek / Ollama 兼容接口，与原版一致的玩法）
- 桌面小组件与通知提醒
