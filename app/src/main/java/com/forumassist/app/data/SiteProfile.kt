package com.forumassist.app.data

/**
 * 站点画像：把一个站点上「模板差异导致」的解析/渲染策略集中开关。
 *
 * 背景：各站的 Discuz 模板差别很大，为某个站点写的兼容逻辑如果全局生效，
 * 很容易把别的站点解析坏。所以这里按站点开关，默认全部关闭 ——
 * 目前只有预设里的「尚香书苑」打开增强策略，其他站点行为与之前完全一致。
 */
data class SiteProfile(
    /** 附件解析增强：dl.tattl / ignore_js_op / 图片附件 / 付费附件 / 无权限提示 */
    val richAttachment: Boolean = false,
    /** 图片请求带上帖子页 Referer（防盗链站点不加会 403 或返回占位图） */
    val imageReferer: Boolean = false,
    /** 详情页多个候选入口并发探测，先成功先返回，避免串行等到超时 */
    val parallelDetail: Boolean = false,
    /** 超长正文按段落拆成 LazyColumn 的独立 item，只排版可视区域 */
    val splitLongPost: Boolean = false,
    /** 详情内存缓存（短 TTL），返回再进不用重新抓 */
    val cacheDetail: Boolean = false,
    /** 附件点击走站内 WebView（带登录态），而不是丢给外部浏览器 */
    val openAttachmentInApp: Boolean = false
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
            openAttachmentInApp = true
        )

        fun of(site: Site?): SiteProfile {
            if (site == null) return Default
            // 站点会被「地址发布页」换成新域名，所以名称和域名都要判断
            val hit = site.name.contains("尚香") || site.baseUrl.contains("sxsy", ignoreCase = true)
            return if (hit) Shangxiang else Default
        }
    }
}

val Site.profile: SiteProfile
    get() = SiteProfile.of(this)
