package com.forumassist.app.data

/**
 * 通用站点适配规则。
 *
 * 三个预设站点（尚香书苑 / 搜书吧 / 老王论坛）的适配经验被提炼成一组规则，
 * 用户新添加的站点先走这套通用规则自动适配，个别站点再在 SiteProfile 里开独立开关。
 * 规则与站点解耦：所有判断都基于页面特征，不针对特定域名。
 *
 * ## R1 引擎识别
 * 页面含 discuz / forum.php / viewthread → Discuz；phpwind → PHPWind；
 * xenforo → XenForo；vbulletin → VBulletin（见 [SiteDetector]）。
 *
 * ## R2 编码自适应
 * 响应头 charset → 页面 meta charset → 该主机的历史编码（见 Http.decode）。
 * GBK 与 UTF-8 站点都无需配置，中文提交也按站点编码发送。
 *
 * ## R3 信息流来源
 * 首页有门户四格（div.subjectbox / replaybox / hottiebox / goodtiebox，
 * 或 h3/h4 文案「最新主题 / 最新回复 / 热帖 / 精华」）→ 走 portal 四格；
 * 否则回落「导读」（forum.php?mod=guide，view=new/newreply/hot/digest 四个分栏）。
 * 运行时 portalBundle 解析为空会自动回落导读并熔断 10 分钟，无需配置。
 *
 * ## R4 详情页结构
 * 桌面版 div[id^=post_] + td.t_f / [id^=postmessage_] 为标准正文容器；
 * 详情入口按「列表原始链接 → page=N → 伪静态 thread-N-P-1.html → mobile=no →
 * archiver」顺序探测（详见 ForumClient.threadPage）。
 *
 * ## R5 签到插件探测
 * 首页 / 全站特征字符串识别签到插件：
 *  - sx_signin       → 尚香自研插件（图形验证码 + JSON 接口）
 *  - k_misign        → 迷神签到（GET operation=qiandao&formhash）
 *  - dsu_paulsign    → DSU 心情签到（最常见的 Discuz 签到插件，表单提交）
 * 探测结果写入 Site.signKind，随站点持久化；预设站点用 SiteProfile 里的内置值。
 *
 * ## R6 登录墙
 * 首页即登录表单（loginsubmit / loginform / 「没有账号」）→ 提示用户先去网页模式登录。
 *
 * ## R7 图片防盗链
 * 正文图片请求默认带帖子页 Referer（与浏览器行为一致）；
 * 带 Referer 失败会自动去掉 Referer 重试（见 PostImage），两个方向都有退路。
 */
object SiteAdaptation {

    /** 适配报告：添加站点时生成，摘要在提示里展示 */
    data class Report(
        val engine: SiteKind,
        /** 信息流来源：portal（首页四格）或 guide（导读） */
        val homeMode: String,
        /** 探测到的签到插件：sx / kmisign / dsu，"" 表示没探测到 */
        val signPlugin: String,
        /** 首页即登录墙 */
        val loginWall: Boolean,
        /** 给用户看的适配摘要 */
        val notes: List<String>
    ) {
        val summary: String
            get() = notes.joinToString("；").ifBlank { "未识别到可用特征，按通用 Discuz 规则处理" }
    }

    /** 已知签到插件 → signKind 的特征表 */
    private val SIGN_PLUGINS = listOf(
        "sx_signin" to "sx",
        "k_misign" to "kmisign",
        "dsu_paulsign" to "dsu"
    )

    fun analyze(html: String, engine: SiteKind = SiteKind.DISCUZ): Report {
        val h = html.lowercase()
        val engineHit = SiteDetector.detectType(html)

        // R3 信息流来源
        val portal = listOf("subjectbox", "replaybox", "hottiebox", "goodtiebox")
            .any { h.contains(it) } ||
            listOf("最新主题", "最新回复", "热帖", "精华").count { html.contains(it) } >= 2 &&
            Regex("<h[1-6][^>]*>[^<]*(?:最新主题|最新回复|热帖|精华)").containsMatchIn(html)
        val homeMode = if (portal) "portal" else "guide"

        // R5 签到插件
        val sign = SIGN_PLUGINS.firstOrNull { (key, _) -> h.contains(key) }?.second.orEmpty()

        // R6 登录墙
        val loginWall = h.contains("loginsubmit") &&
            (h.contains("loginform") || html.contains("没有账号") || html.contains("没有帐号"))

        val notes = buildList {
            add("引擎：${engineHit.label}")
            add("信息流：$homeMode")
            add(
                when (sign) {
                    "sx" -> "签到：sx_signin（验证码）"
                    "kmisign" -> "签到：k_misign"
                    "dsu" -> "签到：DSU 心情签到"
                    else -> "签到：未发现签到插件"
                }
            )
            if (loginWall) add("⚠ 首页是登录墙，请先登录")
        }
        return Report(engineHit, homeMode, sign, loginWall, notes)
    }

    /**
     * R5 通用签到表单解析：在页面里找到签到插件的 <form>，把 action、
     * 隐藏域与默认值一并抓出来（DSU 心情签到用；k_misign 是纯 GET 不需要）。
     * 返回 (action, fields)，找不到返回 null。
     */
    fun parseSignForm(html: String): Pair<String, Map<String, String>>? {
        val doc = runCatching { org.jsoup.Jsoup.parse(html) }.getOrNull() ?: return null
        val form = doc.select("form").firstOrNull {
            it.attr("action").contains("dsu_paulsign") && it.attr("action").contains("qiandao")
        } ?: doc.select("form[id=DSU_signform], form[id^=DSU_]").firstOrNull() ?: return null
        val fields = HashMap<String, String>()
        form.select("input[type=hidden]").forEach {
            val name = it.attr("name")
            if (name.isNotBlank()) fields[name] = it.attr("value")
        }
        // 心情单选：默认选第一个（value 通常是 kx/ym/ng/…）
        form.select("input[type=radio][name=qdxq]").firstOrNull()?.let {
            fields[it.attr("name")] = it.attr("value").ifBlank { "kx" }
        }
        if (fields.containsKey("qdxq").not()) fields["qdxq"] = "kx"
        fields.putIfAbsent("qdmode", "1")
        val action = form.absUrl("action").ifBlank { form.attr("action") }
        if (action.isBlank()) return null
        return action to fields
    }
}
