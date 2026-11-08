package com.forumassist.app.data

/**
 * 轻量广告/统计拦截：参考常见广告屏蔽插件（AdAway / uBlock 的 host 规则）做法，
 * 在 WebView 请求层直接短路，并注入一段保守的「元素隐藏」脚本清理广告位。
 */
object AdBlock {

    /** 常见广告联盟 / 统计 / 追踪域名（子串匹配，覆盖其子域） */
    private val hostKeywords = listOf(
        // 国际广告联盟
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagmanager.com", "google-analytics.com", "adservice.google",
        "amazon-adsystem.com", "ads.yahoo.com", "media.net", "adnxs.com",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "popads.net", "propellerads.com", "adsterra.com", "exoclick.com",
        "juicyads.com", "trafficjunky.com", "adcolony.com", "applovin.com",
        "unityads.unity3d.com", "ironsrc.com", "mopub.com", "adkernel.com",
        // 国内广告 / 统计
        "cpro.baidu.com", "pos.baidu.com", "hm.baidu.com", "eclick.baidu.com",
        "cbjs.baidu.com", "union.baidu.com", "tanx.com", "alimama.com",
        "mmstat.com", "simba.taobao.com", "cnzz.com", "umeng.com",
        "51.la", "51yes.com", "clarity.ms", "growingio.com", "seniverse.com"
    )

    /** 明显的广告路径特征 */
    private val pathKeywords = listOf(
        "/adserver", "/adframe", "/popunder", "/ad_banner", "/adsense/",
        "banner_ad", "ad_slot", "adzone", "adview"
    )

    fun shouldBlock(url: String): Boolean {
        val u = url.lowercase()
        if (u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("about:")) return false
        if (hostKeywords.any { u.contains(it) }) return true
        return pathKeywords.any { u.contains(it) }
    }

    /**
     * 注入页面的广告位隐藏脚本（保守：只处理标识明确的广告容器，避免误伤正文）。
     * 返回隐藏的元素个数，便于调试。
     */
    const val COSMETIC_JS = """(function(){try{
var sels=[".adsbygoogle","#ad_top","#ad_footer","#ad_banner",".advert",".advertisement",
".ad-container",".ad_box",".adbox",".ads-box",".ad-wrap",
"iframe[src*='doubleclick']","iframe[src*='googlesyndication']","iframe[src*='/adserver']",
"a[href*='cpro.baidu.com']","img[src*='cpro.baidu.com']"];
var n=0;
sels.forEach(function(s){try{document.querySelectorAll(s).forEach(function(e){
if(e && e.style){e.style.display='none';n++;}});}catch(e){}});
return n;}catch(e){return -1}})()"""
}
