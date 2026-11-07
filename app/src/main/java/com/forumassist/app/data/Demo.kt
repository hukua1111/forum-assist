package com.forumassist.app.data

/**
 * 演示站点：不联网也能完整体验 App 的阅读、收藏、离线、发帖流程。
 */
object Demo {

    const val SITE_ID = "demo-site"

    private val boardDefs = listOf(
        Triple("2", "综合讨论", "天南海北，随便聊聊"),
        Triple("3", "数码与硬件", "手机 / NAS / 路由器 / 外设"),
        Triple("4", "软件与工具", "效率工具、开源项目、脚本"),
        Triple("5", "摄影与生活", "相机、镜头、旅行见闻"),
        Triple("6", "二手交易", "闲置转让，诚信第一")
    )

    fun site(): Site = Site(
        id = SITE_ID,
        name = "演示论坛",
        baseUrl = "https://demo.forum",
        kind = SiteKind.DEMO,
        isDemo = true,
        color = 0xFF12B886.toInt()
    )

    fun boards(): List<Board> = boardDefs.mapIndexed { i, (fid, name, desc) ->
        Board(SITE_ID, fid, name, desc, todayPosts = 40 + i * 13)
    }

    private data class Tpl(
        val title: String,
        val board: String,
        val author: String,
        val replies: Int,
        val views: Int,
        val digest: Boolean = false,
        val top: Boolean = false,
        val body: List<String>
    )

    private val templates = listOf(
        Tpl(
            "折腾了三个周末，终于把家里的老 NAS 换成了 N100 小主机",
            "数码与硬件", "夜航船", 128, 6421, digest = true,
            body = listOf(
                "原来的 J3455 板子跑了五年，最近转码 4K 实在吃力，索性整套换掉。",
                "配置：N100 工控板 + 16G 内存 + 两块 4T 硬盘做 RAID1，系统还是黑群晖，数据直接迁移，没有重装。",
                "实测功耗待机 9W，满载 28W，比原来低了一截，风扇基本听不到声音。Jellyfin 硬解 4K HEVC 无压力，手机在外面看也不卡了。",
                "唯一的问题是这块板子只有一个 SATA 口，扩展要靠 M.2 转接，走线比较别扭。有同样方案的朋友可以交流下机箱选择。"
            )
        ),
        Tpl(
            "有没有人像我一样，把手机上的 App 精简到了 20 个以内",
            "综合讨论", "老猫", 96, 3877,
            body = listOf(
                "上个月开始做减法，把能用网页版替代的全删了，现在主屏两页就放得下。",
                "最明显的感受是：拿起手机不知道干什么的时间变少了，屏幕使用时间从每天 6 小时降到 3 小时出头。",
                "留下的基本都是刚需：通讯、支付、地图、音乐、笔记，还有这个论坛客户端。"
            )
        ),
        Tpl(
            "分享一个自用的 Frp 内网穿透配置，稳定跑了两年",
            "软件与工具", "CoderX", 74, 5120, digest = true,
            body = listOf(
                "服务端用的最便宜的 1C1G 小鸡，只做转发，压力很小。",
                "配置文件我贴在下面，重点是开了 kcp 和 tls，弱网下比裸 tcp 稳很多。",
                "客户端这边用 systemd 托管，掉线自动重连，两年里基本没管过。注意一定要限制 token，不然端口容易被扫。"
            )
        ),
        Tpl(
            "秋天去了一趟川西，拍了几张，欢迎拍砖",
            "摄影与生活", "半山", 56, 2984,
            body = listOf(
                "机身是二手收的 A7M3，镜头只有一颗 24-105，全程手持。",
                "海拔四千米以上光线特别硬，早上七点前和傍晚六点后才是黄金时间，中间基本没法拍。",
                "后期只做了基础调整，没敢大动，怕颜色失真。第一次去高原，构图还是太保守了。"
            )
        ),
        Tpl(
            "出闲置：罗技 MX Master 3S，九成新",
            "二手交易", "阿蛮", 12, 866,
            body = listOf(
                "去年双十一买的，用不习惯侧键，一直吃灰。",
                "成色很好，无磕碰，配件盒子都在，价格 380 包邮，走平台。",
                "有意的站内信，看到就回。"
            )
        ),
        Tpl(
            "为什么现在的路由器越做越像游戏机",
            "数码与硬件", "数码宅", 88, 4102,
            body = listOf(
                "八个天线、跑马灯、还有屏幕，摆在客厅像个外星造物。",
                "其实大部分家庭 200M 宽带，一个 200 块的路由器就够用了，剩下的都是心理需求。",
                "不过话说回来，能折腾也是一种乐趣，我自己也没忍住上了 WiFi7。"
            )
        ),
        Tpl(
            "推荐一个命令行工具，能直接搜论坛历史帖",
            "软件与工具", "青柠", 41, 1930,
            body = listOf(
                "自己写的小脚本，用站点自带的搜索接口，输出成 markdown 存档。",
                "好处是可以在本地做全文检索，找以前看过的技术帖特别方便。",
                "代码放在自己的 Git 上，需要的可以留言，我整理一下发出来。"
            )
        ),
        Tpl(
            "关于「降噪耳机能不能代替耳塞」的一点经验",
            "综合讨论", "鹿鸣", 63, 2411,
            body = listOf(
                "坐飞机试过几次，结论是能代替一半。",
                "低频轰鸣降得很好，但人声、婴儿哭闹这类中高频还是挡不住，长途还是耳塞 + 降噪叠加最舒服。",
                "另外戴久了耳压不太舒服，建议每小时摘下来透透气。"
            )
        ),
        Tpl(
            "家里老人的手机，大家都是怎么设置的",
            "综合讨论", "南山", 152, 7300,
            body = listOf(
                "我爸七十多了，字要最大，图标要最少，还得防误触。",
                "目前方案：桌面只留 6 个图标，关掉所有通知，装了个远程协助，出问题我直接连过去。",
                "比较头疼的是各种弹窗广告，有没有更彻底的办法？"
            )
        ),
        Tpl(
            "机械键盘的终点到底是什么",
            "数码与硬件", "键圈老张", 71, 3021,
            body = listOf(
                "从青轴玩到静电容，从 104 键玩到 40%，最后又回到了 87 键红轴。",
                "折腾了七八把，发现真正每天用的还是最朴素的那把。",
                "所以我的答案是：终点是「不折腾」。"
            )
        ),
        Tpl(
            "用旧笔记本做软路由，跑了半年的一些记录",
            "软件与工具", "老K", 45, 2201,
            body = listOf(
                "i5-6200U 双网口，OpenWrt 装在 U 盘里，功耗大概 12W。",
                "好处是性能过剩，跑各种插件都很轻松；坏处是体积大、没有硬件加速。",
                "半年下来没重启过，稳定性比想象中好。"
            )
        ),
        Tpl(
            "周末去了城郊的一个旧书市，收获不错",
            "摄影与生活", "木鱼", 28, 1440,
            body = listOf(
                "淘到两本八十年代的摄影集，纸张发黄，但印刷质感是现在比不了的。",
                "摊主是个退休老师，聊了半小时胶片时代的事。",
                "顺手拍了几张，光线一般，主要记录气氛。"
            )
        ),
        Tpl(
            "求推荐：给爸妈装一个能看 CCTV 的电视盒子",
            "数码与硬件", "小鱼", 37, 1688,
            body = listOf(
                "要求很简单：开机就能看直播，别有一堆广告，遥控器按键大。",
                "自己折腾过几个方案，老人学不会，最后还是要「能用遥控器直接按」的。",
                "预算 500 以内，有实际用过的朋友说说。"
            )
        ),
        Tpl(
            "把博客从 WordPress 迁到了静态站点，说说踩的坑",
            "软件与工具", "阿茶", 52, 2604,
            body = listOf(
                "导出了一千多篇文章，图片全部搬到对象存储。",
                "域名没换，301 规则写了半天，索引恢复大概用了三周。",
                "现在打开速度确实快，构建全自动，写完 push 就行。"
            )
        ),
        Tpl(
            "楼下的早餐店换了老板，味道变了",
            "综合讨论", "陈二", 19, 812,
            body = listOf(
                "开了十几年的一家店，上个月突然换了人。",
                "豆浆还是那个价，但明显稀了，油条也不脆。",
                "有点感慨，很多熟悉的东西就是这么慢慢消失的。"
            )
        ),
        Tpl(
            "准备入手第一台相机，预算 5000 左右求建议",
            "摄影与生活", "小满", 84, 3320,
            body = listOf(
                "主要拍人像和日常，偶尔旅拍，不打算接活。",
                "在残幅微单和老款全幅之间纠结，二手也可以接受。",
                "更看重直出色彩和轻便，参数倒是其次。"
            )
        ),
        Tpl(
            "出：群晖 DS220+ 一台，含两块 2T 红盘",
            "二手交易", "老周", 8, 640,
            body = listOf(
                "升级四盘位了，这台闲置。",
                "机器无拆无修，硬盘通电时间 8000 小时左右，健康。",
                "整机 1500，同城自提优先。"
            )
        ),
        Tpl(
            "你们还会认真看完一篇长帖吗",
            "综合讨论", "路人甲", 205, 9012,
            body = listOf(
                "发现自己越来越没耐心，超过三屏就想划走。",
                "但真正写得好的长帖，看完还是会觉得值，甚至收藏起来反复看。",
                "可能问题不在长度，而在于有没有干货。"
            )
        )
    )

    private val replyAuthors = listOf(
        "夜航船", "老猫", "CoderX", "半山", "阿蛮", "数码宅", "青柠", "鹿鸣",
        "南山", "键圈老张", "老K", "木鱼", "小鱼", "阿茶", "陈二", "小满", "路人甲"
    )

    private val replyPool = listOf(
        "同款方案，Mark 一下，回头细看。",
        "感谢分享，正好最近在纠结这个。",
        "楼主说得挺实在的，比那些恰饭测评强多了。",
        "我这边实测数据差不多，差距在误差范围内。",
        "有一个疑问：长期用下来稳定性怎么样？",
        "收藏了，周末照着折腾一遍。",
        "补充一点，注意散热，夏天容易翻车。",
        "看完感觉自己又行了，然后打开购物车。",
        "这个思路挺有意思，之前完全没往这个方向想。",
        "坐标南方，湿度大，我这边用了半年没什么问题。",
        "预算够的话建议一步到位，来回换更费钱。",
        "先顶再看，楼主人品不错。",
        "这帖质量可以，版主应该加精。",
        "我踩过这个坑，细节在 12 楼。",
        "有链接吗？搜了半天没找到。"
    )

    fun threads(boardName: String? = null, limit: Int = 60): List<ThreadSummary> {
        val now = System.currentTimeMillis()
        return templates.mapIndexedNotNull { i, t ->
            if (boardName != null && t.board != boardName) return@mapIndexedNotNull null
            val board = boardDefs.firstOrNull { it.second == t.board }
            ThreadSummary(
                siteId = SITE_ID,
                tid = "d${1000 + i}",
                fid = board?.first ?: "2",
                title = t.title,
                author = t.author,
                replies = t.replies,
                views = t.views,
                lastTime = TimeUtil.pretty(now - (i * 37 + 8) * 60_000L),
                lastReplier = replyAuthors[(i * 3) % replyAuthors.size],
                boardName = t.board,
                isTop = t.top,
                isDigest = t.digest,
                ts = now - (i * 37 + 8) * 60_000L
            )
        }.take(limit)
    }

    fun detail(tid: String): ThreadDetail? {
        val index = tid.removePrefix("d").toIntOrNull()?.minus(1000) ?: return null
        val t = templates.getOrNull(index) ?: return null
        val board = boardDefs.firstOrNull { it.second == t.board }
        val now = System.currentTimeMillis()
        val summary = ThreadSummary(
            siteId = SITE_ID,
            tid = tid,
            fid = board?.first ?: "2",
            title = t.title,
            author = t.author,
            replies = t.replies,
            views = t.views,
            lastTime = TimeUtil.pretty(now - 30 * 60_000),
            boardName = t.board,
            isDigest = t.digest,
            ts = now - 3_600_000
        )
        val posts = mutableListOf<Post>()
        posts.add(
            Post(
                pid = "${tid}_0",
                floor = 1,
                author = t.author,
                time = TimeUtil.pretty(now - (index + 2) * 3_600_000L),
                html = t.body.joinToString("") { "<p>$it</p>" },
                blocks = t.body.map { Block.Text(it) },
                isOp = true
            )
        )
        val count = minOf(9, maxOf(3, t.replies / 12))
        for (i in 0 until count) {
            val paragraphs = buildList {
                add(replyPool[(index * 3 + i) % replyPool.size])
                if (i % 3 == 0) add(replyPool[(index + i * 2 + 5) % replyPool.size])
            }
            posts.add(
                Post(
                    pid = "${tid}_${i + 1}",
                    floor = i + 2,
                    author = replyAuthors[(index + i * 5) % replyAuthors.size],
                    time = TimeUtil.pretty(now - (count - i) * 47 * 60_000L),
                    html = paragraphs.joinToString("") { "<p>$it</p>" },
                    blocks = paragraphs.map { Block.Text(it) }
                )
            )
        }
        return ThreadDetail(summary, posts, 1, maxOf(1, t.replies / 10))
    }

    fun search(keyword: String): List<ThreadSummary> =
        threads().filter { it.title.contains(keyword, true) || it.author.contains(keyword, true) }
}
