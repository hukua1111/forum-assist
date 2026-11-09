package com.forumassist.app.data

/**
 * 站点画像：把一个站点上「模板差异导致」的解析/渲染策略集中开关。
 *
 * 背景：各站的 Discuz 模板差别很大，为某个站点写的兼容逻辑如果全局生效，
 * 很容易把别的站点解析坏。所以这里按站点开关 ——
 * 预设三站点各有一份画像；用户新添加的站点走 SiteAdaptation 通用规则，
 * 探测结果（签到插件）落到 Site.signKind，其余策略用 Default。
 */
data class SiteProfile(
    /** 附件解析增强：dl.tattl / ignore_js_op / 图片附件 / 付费附件 / 无权限提示 */
    val richAttachment: Boolean = false,
    /**
     * 图片请求带上帖子页 Referer（R7 通用规则：与浏览器行为一致）。
     * 带 Referer 失败时 PostImage 会自动去掉 Referer 重试，两个方向都有退路。
     */
    val imageReferer: Boolean = true,
    /** 详情页多个候选入口并发探测，先成功先返回，避免串行等到超时 */
    val parallelDetail: Boolean = false,
    /** 超长正文按段落拆成 LazyColumn 的独立 item，只排版可视区域 */
    val splitLongPost: Boolean = false,
    /** 详情内存缓存（短 TTL），返回再进不用重新抓 */
    val cacheDetail: Boolean = false,
    /** 附件点击走站内 WebView（带登录态），而不是丢给外部浏览器 */
    val openAttachmentInApp: Boolean = false,

    // ---------------------------------------------------------------- 搜书吧类模板（列表/发布页差异）

    /** 首页只有一页四格、没有下一页（翻页时不再拿「导读」内容硬凑） */
    val portalOnly: Boolean = false,
    /** 列表标题被模板截断成「…」，完整书名在 title 属性里 */
    val titleFromAttr: Boolean = false,
    /** 「最新回复」条目是 redirect&goto=lastpost，需要还原成标准详情地址 */
    val normalizeRedirect: Boolean = false,
    /** 导读里「最新回复」的 view 参数（Discuz 官方是 new，不是 newreply） */
    val guideReplyView: String = "",
    /** 地址发布页是多级跳转（永久地址 → 中转域名 → 落地页「最新地址」） */
    val chainPublisher: Boolean = false,

    // ---------------------------------------------------------------- 每日签到机制

    /** 签到插件类型：sx=sx_signin（带验证码）；kmisign=k_misign；visit=登录访问即奖励；空=不支持 */
    val signKind: String = ""
) {
    companion object {
        val Default = SiteProfile()

        /** 尚香书苑：长章节正文 + 付费附件 + 防盗链 + 采集防护，需要全套增强 */
        val Shangxiang = SiteProfile(
            richAttachment = true,
            imageReferer = true,
            parallelDetail = true,
            splitLongPost = true,
            cacheDetail = true,
            openAttachmentInApp = true,
            // 自研签到插件 sx_signin：POST op=signin&formhash&seccode（要图形验证码）
            signKind = "sx"
        )

        /**
         * 搜书吧：Discuz X3.4 + GBK + 自定义模板。
         * 首页四格（最新主题 / 最新回复 / 热帖 / 精华）只有截断标题，完整书名在 title 属性里；
         * 「最新回复」给的是 redirect&goto=lastpost，要还原成详情地址才能读到正文。
         * 只开这几项，附件/正文仍走通用逻辑，不影响别的站点。
         */
        val Soushu = SiteProfile(
            // 附件图有防盗链：不带帖子页 Referer 时站点会返回一张「加载中」的绿色占位图
            imageReferer = true,
            portalOnly = true,
            titleFromAttr = true,
            normalizeRedirect = true,
            guideReplyView = "new",
            chainPublisher = true,
            // 没有签到插件：官方攻略「每天登陆即可得 2 银币」= 登录态访问一次
            signKind = "visit"
        )

        /**
         * 老王论坛：Discuz X3.4 + UTF-8 + 标准桌面模板（dean 皮肤）。
         * 首页是视频门户（无四格）→ portalBundle 必空，走标准「导读」当信息流；
         * 导读/正文/登录全是标准 Discuz 结构，通用解析直接可用。
         * 附件存在 /remote/data/attachment/，图片请求带帖子页 Referer 与浏览器行为一致。
         */
        val Laowang = SiteProfile(
            imageReferer = true,
            // k_misign（迷神签到）插件：operation=qiandao&formhash
            signKind = "kmisign"
        )

        fun of(site: Site?): SiteProfile {
            if (site == null) return Default
            // 站点会被「地址发布页」换成新域名，所以名称和域名都要判断
            val preset = when {
                site.name.contains("老王") || site.baseUrl.contains("laowang", ignoreCase = true) -> Laowang
                site.name.contains("搜书吧") ||
                    site.baseUrl.contains("soushu", ignoreCase = true) ||
                    site.baseUrl.contains("bew2459m", ignoreCase = true) ||
                    site.baseUrl.contains("allshu", ignoreCase = true) -> Soushu
                site.name.contains("尚香") || site.baseUrl.contains("sxsy", ignoreCase = true) -> Shangxiang
                else -> Default
            }
            // 添加站点时通用适配（SiteAdaptation）探测到的签到插件写入 site.signKind，
            // 探测结果优先于预设内置值——这样预设站点换插件也能跟上，新站点直接用探测值
            val sk = site.signKind.trim()
            return if (sk.isNotBlank() && sk != preset.signKind) preset.copy(signKind = sk) else preset
        }
    }
}

val Site.profile: SiteProfile
    get() = SiteProfile.of(this)
