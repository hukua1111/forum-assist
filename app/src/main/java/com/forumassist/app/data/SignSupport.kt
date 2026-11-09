package com.forumassist.app.data

import org.json.JSONObject

/**
 * 每日签到。三个预设站点的「签到」机制完全不同，按 SiteProfile.signKind 分派，
 * 站点之间互不影响：
 *
 *  - 尚香书苑（sx_signin 自定义插件）：状态在签到页的 window.SIGNIN JSON 里
 *    （signed / formhash / captcha / captchaUrl）；签到动作是
 *    POST plugin.php?id=sx_signin:ajax，body op=signin&formhash&seccode，
 *    需要图形验证码（验证码与站点会话 Cookie 绑定）；响应 JSON code=0 成功 / 2 已签。
 *
 *  - 老王论坛（k_misign 迷神签到）：标准协议 GET
 *    plugin.php?id=k_misign:sign&operation=qiandao&formhash=…，
 *    按响应文本判断成功 / 已签。保存样本是游客态拿不到登录态按钮，
 *    所以判定用宽泛的文案匹配，兜底给出「结果未知」而不是误报成功。
 *
 *  - 通用 dsu_paulsign（DSU 心情签到，Discuz 最常见的签到插件）：
 *    从签到页解析 <form>（SiteAdaptation.parseSignForm），带默认心情提交。
 *    用户新添加的站点经 SiteAdaptation 探测到该插件后自动启用。
 *
 *  - 搜书吧：没有签到插件。官方攻略里唯一的「每日任务」是登录奖励——
 *    每天登录访问站点即自动 +2 银币，所以签到动作就是带登录态访问一次首页。
 */
object SignSupport {

    /** 探测结果：要不要验证码、是否已签，以及签到所需的 formhash */
    data class SignStatus(
        val formhash: String = "",
        val signedToday: Boolean = false,
        val needCaptcha: Boolean = false,
        val captchaUrl: String = "",
        /** 探测失败时给 UI 的提示（仍可尝试 sign 兜底） */
        val note: String = ""
    )

    sealed class SignResult {
        data class Success(val message: String) : SignResult()
        data class Already(val message: String) : SignResult()
        data class Failure(val message: String) : SignResult()
    }

    fun supported(site: Site): Boolean = site.profile.signKind.isNotBlank()

    fun probe(site: Site, http: Http): SignStatus = when (site.profile.signKind) {
        "sx" -> probeSx(site, http)
        "kmisign" -> probeKmisign(site, http)
        else -> SignStatus(note = "每天登录访问即可获得奖励")
    }

    fun sign(site: Site, http: Http, seccode: String? = null): SignResult =
        when (site.profile.signKind) {
            "sx" -> signSx(site, http, seccode)
            "kmisign" -> signKmisign(site, http)
            "dsu" -> signDsu(site, http)
            "visit" -> signVisit(site, http)
            else -> SignResult.Failure("该站点不支持签到")
        }

    // ---------------------------------------------------------------- 尚香 sx_signin

    private fun sxBase(site: Site) = site.baseUrl.trimEnd('/')

    private fun probeSx(site: Site, http: Http): SignStatus {
        val base = sxBase(site)
        val html = http.get("$base/plugin.php?id=sx_signin:index", desktop = true)
            ?: return SignStatus(note = "签到页打开失败（网络或登录态问题）")
        val json = Regex("window\\.SIGNIN\\s*=\\s*(\\{.*?\\})\\s*;").find(html)
            ?.let { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }
            ?: return SignStatus(note = "签到页结构不认识（可能未登录）")
        val signed = json.optInt("signed", -1)
        val formhash = json.optString("formhash")
        val captcha = json.optInt("captcha", 0) == 1
        return SignStatus(
            formhash = formhash,
            signedToday = signed == 1,
            needCaptcha = captcha && signed == 0,
            captchaUrl = json.optString("captchaUrl").ifBlank { "$base/plugin.php?id=sx_signin:captcha" }
        )
    }

    private fun signSx(site: Site, http: Http, seccode: String?): SignResult {
        val base = sxBase(site)
        val st = probeSx(site, http)
        if (st.signedToday) return SignResult.Already("今天已经签到过了")
        if (st.formhash.isBlank()) return SignResult.Failure(st.note.ifBlank { "签到页解析失败" })
        if (st.needCaptcha && seccode.isNullOrBlank()) {
            return SignResult.Failure("需要输入验证码")
        }
        val form = buildMap {
            put("op", "signin")
            put("formhash", st.formhash)
            seccode?.takeIf { it.isNotBlank() }?.let { put("seccode", it) }
        }
        val resp = http.post(
            "$base/plugin.php?id=sx_signin:ajax", form,
            referer = "$base/plugin.php?id=sx_signin:index"
        ) ?: return SignResult.Failure("签到请求失败")
        val json = runCatching { JSONObject(resp) }.getOrNull()
            ?: return SignResult.Failure("签到响应异常：${resp.take(80)}")
        return when (json.optInt("code", -1)) {
            0 -> {
                val data = json.optJSONObject("data")
                val credit = data?.optString("credit").orEmpty()
                val streak = data?.optString("streak").orEmpty()
                val parts = buildList {
                    if (credit.isNotBlank()) add("奖励 $credit")
                    if (streak.isNotBlank()) add("连续 $streak 天")
                }
                SignResult.Success(if (parts.isEmpty()) "签到成功" else "签到成功 · " + parts.joinToString("，"))
            }
            2 -> SignResult.Already(json.optString("msg", "今天已经签到过了"))
            else -> SignResult.Failure(json.optString("msg", "签到失败"))
        }
    }

    // ---------------------------------------------------------------- 老王 k_misign

    private const val KMISIGN_PAGE = "plugin.php?id=k_misign:sign"

    private fun probeKmisign(site: Site, http: Http): SignStatus {
        val base = site.baseUrl.trimEnd('/')
        val html = http.get("$base/$KMISIGN_PAGE", desktop = true)
            ?: return SignStatus(note = "签到页打开失败（网络或登录态问题）")
        // 未登录时页面里根本没有 formhash，sign 会以「需要先登录」失败
        return SignStatus(formhash = formhashOf(html), signedToday = html.contains("已签到"))
    }

    private fun signKmisign(site: Site, http: Http): SignResult {
        val base = site.baseUrl.trimEnd('/')
        val st = probeKmisign(site, http)
        if (st.formhash.isBlank()) return SignResult.Failure("需要先登录（签到页没有 formhash）")
        val resp = http.get(
            "$base/$KMISIGN_PAGE&operation=qiandao&formhash=${st.formhash}",
            referer = "$base/$KMISIGN_PAGE",
            desktop = true
        ) ?: return SignResult.Failure("签到请求失败")
        return when {
            resp.contains("签到成功") || resp.contains("succeedhandle") ->
                SignResult.Success("签到成功")
            resp.contains("已签到") || resp.contains("已经签到") || resp.contains("重复签到") ->
                SignResult.Already("今天已经签到过了")
            resp.contains("行动失败") || resp.contains("您需要先登录") ->
                SignResult.Failure("需要先登录")
            else -> SignResult.Failure("签到结果未知（响应：${resp.take(100)}）")
        }
    }

    // ---------------------------------------------------------------- 通用 DSU 心情签到

    private fun signDsu(site: Site, http: Http): SignResult {
        val base = site.baseUrl.trimEnd('/')
        val html = http.get("$base/plugin.php?id=dsu_paulsign:sign", desktop = true)
            ?: return SignResult.Failure("签到页打开失败（网络或登录态问题）")
        if (html.contains("已经签到") || html.contains("已签到，明天")) {
            return SignResult.Already("今天已经签到过了")
        }
        val (action, fields) = SiteAdaptation.parseSignForm(html)
            ?: return SignResult.Failure("没找到签到表单（可能需要先登录）")
        val referer = "$base/plugin.php?id=dsu_paulsign:sign"
        val resp = http.post(action, fields, referer = referer)
            ?: return SignResult.Failure("签到请求失败")
        return when {
            resp.contains("签到成功") || resp.contains("succeedhandle") ->
                SignResult.Success("签到成功")
            resp.contains("已签") || resp.contains("已经签到") || resp.contains("重复签到") ->
                SignResult.Already("今天已经签到过了")
            resp.contains("您需要先登录") || resp.contains("权限") ->
                SignResult.Failure("需要先登录")
            else -> SignResult.Failure("签到结果未知（响应：${resp.take(100)}）")
        }
    }

    // ---------------------------------------------------------------- 搜书吧 登录奖励

    private fun signVisit(site: Site, http: Http): SignResult {
        // 攻略原文：「每天登陆搜书吧即可得到2银币的奖励」——登录态访问一次即达成
        val html = http.get(site.baseUrl.trimEnd('/') + "/forum.php?mobile=no", desktop = true)
            ?: return SignResult.Failure("访问站点失败")
        if (html.contains("member.php?mod=logging&action=login") && html.contains("没有账号")) {
            return SignResult.Failure("登录态已失效，请重新登录")
        }
        return SignResult.Success("已完成每日登录访问，登录奖励（+2 银币）由站点自动发放")
    }

    // ---------------------------------------------------------------- 工具

    private fun formhashOf(html: String): String =
        Regex("formhash=([0-9a-zA-Z]{6,})").find(html)?.groupValues?.get(1)
            ?: Regex("name=[\"']formhash[\"'][^>]*value=[\"']([^\"']+)[\"']")
                .find(html)?.groupValues?.get(1).orEmpty()
}
