# 论坛助手 · Android

一个专注「掌上阅读论坛」的 Android 客户端，参考 iOS 端《论坛助手》的产品思路重新实现：
把多个 Discuz / PHPWind / XenForo 论坛聚合成一条时间线，用原生页面流畅地读帖、回帖、收藏与离线保存。

> 这是个人学习 / 自用项目，与《论坛助手》官方无关。所有内容版权归各论坛及原作者所有。

---

## 1. 已实现功能

| 模块 | 说明 |
| --- | --- |
| 多站点聚合 | 首页把已启用站点的「最新发表 / 最新回复 / 热门」合并成一条信息流，并发抓取、自动去重排序 |
| 站点管理 | 粘贴网址自动识别站点类型（Discuz / PHPWind / XenForo / Vbulletin），支持启用、删除、排序色标 |
| 版块浏览 | 自动解析版块列表 → 主题列表，支持最新回复 / 最新发表 / 精华排序与分页加载 |
| 帖子阅读 | 楼层卡片、楼主标识、头像、引用块、图片块、长按可选文本；自动记录阅读位置并显示「+N 新回复」 |
| 发帖 / 回复 | Discuz 表单自动取 formhash 提交，支持标题、正文与表情快捷插入 |
| 登录 | Discuz 账号密码登录，Cookie 持久化在本机（不上传任何服务器） |
| 收藏 / 离线 / 历史 | 本地 JSON 存储，无网可读；支持一键离线整帖 |
| 搜索 | 跨站点标题搜索 |
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

1. 首次打开只预置「尚香书苑」一个站点（域名变了会从地址发布页自动纠正）。
2. 「论坛」页点右上角「添加」，粘贴任意 Discuz 论坛首页地址即可，App 会探测站点类型。
3. 阅读：首页卡片 → 帖子详情；右上角可收藏 / 离线 / 复制链接 / 分享 / 网页打开。
4. 发帖与回复：需要先在该站点「登录」。部分站点开启验证码或安全提问，这类账号请用网页模式登录。
5. 抓不到内容时，优先切「网页模式」——内置 WebView 与站点共享 Cookie，已登录状态可直接发帖。
6. 小说站的正文常常整个放在附件里（帖子只有一句简介），点附件 →「在线阅读」即可直接看 txt，不用先下载再找文件管理器；GBK 编码的旧文本会自动识别。

## 6. 已知限制

- 各论坛模板差异极大，解析器采用多套选择器降级，仍可能对个别站点失效（此时会自动回落到网页模式提示）。
- 验证码 / 滑块 / 安全提问类登录无法在原生表单完成。
- 暂不支持本地相册上传图片（可先上传图床再粘贴链接）。
- 只读接口遵循各站点公开页面，请控制刷新频率，遵守版规与 robots 协议。

## 7. 后续可做

- 图片上传、草稿箱、楼层定位、只看楼主、子帖提取
- 多账号切换、签到插件适配
- AI 辅助回帖（DeepSeek / Ollama 兼容接口，与原版一致的玩法）
- 桌面小组件与通知提醒
