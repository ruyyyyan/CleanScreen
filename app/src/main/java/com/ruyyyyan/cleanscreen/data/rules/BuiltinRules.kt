package com.ruyyyyan.cleanscreen.data.rules

import com.ruyyyyan.cleanscreen.data.model.SkipRule

/**
 * Curated high-precision built-in rules for top popular Chinese applications,
 * inspired by GKD and LiTiaotiao community rule sets.
 */
object BuiltinRules {

    fun getDefaultRules(): List<SkipRule> {
        return listOf(
            // 微信 (WeChat) - Splash is on LauncherUI before entering conversation list
            SkipRule(
                id = "builtin:com.tencent.mm:splash",
                packageName = "com.tencent.mm",
                appName = "微信",
                description = "开屏启动跳过",
                activityIds = listOf("com.tencent.mm.ui.LauncherUI"),
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 微博 (Weibo)
            SkipRule(
                id = "builtin:com.sina.weibo:splash",
                packageName = "com.sina.weibo",
                appName = "微博",
                description = "开屏广告跳过",
                activityIds = listOf("com.sina.weibo.SplashActivity", "com.sina.weibo.MainTabActivity"),
                targetId = "com.sina.weibo:id/skip_view",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 哔哩哔哩 (Bilibili)
            SkipRule(
                id = "builtin:tv.danmaku.bili:splash",
                packageName = "tv.danmaku.bili",
                appName = "哔哩哔哩",
                description = "开屏倒计时跳过",
                activityIds = listOf("tv.danmaku.bili.ui.splash.SplashActivity", "tv.danmaku.bili.MainActivityV2"),
                targetId = "tv.danmaku.bili:id/count_down",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 知乎 (Zhihu)
            SkipRule(
                id = "builtin:com.zhihu.android:splash",
                packageName = "com.zhihu.android",
                appName = "知乎",
                description = "开屏广告跳过",
                targetId = "com.zhihu.android:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 网易云音乐 (NetEase Cloud Music)
            SkipRule(
                id = "builtin:com.netease.cloudmusic:splash",
                packageName = "com.netease.cloudmusic",
                appName = "网易云音乐",
                description = "开屏广告跳过",
                targetId = "com.netease.cloudmusic:id/skip_btn",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 手机淘宝 (Taobao)
            SkipRule(
                id = "builtin:com.taobao.taobao:splash",
                packageName = "com.taobao.taobao",
                appName = "淘宝",
                description = "开屏广告跳过",
                targetId = "com.taobao.taobao:id/splash_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 京东 (JD)
            SkipRule(
                id = "builtin:com.jingdong.app.mall:splash",
                packageName = "com.jingdong.app.mall",
                appName = "京东",
                description = "开屏广告跳过",
                targetId = "com.jingdong.app.mall:id/skip_btn",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 拼多多 (Pinduoduo)
            SkipRule(
                id = "builtin:com.xunmeng.pinduoduo:splash",
                packageName = "com.xunmeng.pinduoduo",
                appName = "拼多多",
                description = "开屏广告跳过",
                targetId = "com.xunmeng.pinduoduo:id/skip_view",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 酷安 (Coolapk)
            SkipRule(
                id = "builtin:com.coolapk.market:splash",
                packageName = "com.coolapk.market",
                appName = "酷安",
                description = "穿山甲开屏跳过",
                targetId = "com.coolapk.market:id/tt_splash_skip_tv",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 百度贴吧 (Baidu Tieba)
            SkipRule(
                id = "builtin:com.baidu.tieba:splash",
                packageName = "com.baidu.tieba",
                appName = "百度贴吧",
                description = "开屏广告跳过",
                targetId = "com.baidu.tieba:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 高德地图 (Amap)
            SkipRule(
                id = "builtin:com.autonavi.minimap:splash",
                packageName = "com.autonavi.minimap",
                appName = "高德地图",
                description = "开屏广告跳过",
                targetId = "com.autonavi.minimap:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 美团 (Meituan)
            SkipRule(
                id = "builtin:com.sankuai.meituan:splash",
                packageName = "com.sankuai.meituan",
                appName = "美团",
                description = "开屏广告跳过",
                targetId = "com.sankuai.meituan:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 抖音 (Douyin / TikTok CN)
            SkipRule(
                id = "builtin:com.ss.android.ugc.aweme:splash",
                packageName = "com.ss.android.ugc.aweme",
                appName = "抖音",
                description = "开屏广告跳过",
                targetId = "com.ss.android.ugc.aweme:id/skip_view",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 快手 (Kuaishou)
            SkipRule(
                id = "builtin:com.smile.gifmaker:splash",
                packageName = "com.smile.gifmaker",
                appName = "快手",
                description = "快手联盟开屏跳过",
                targetId = "com.smile.gifmaker:id/splash_skip_tv",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 小红书 (Xiaohongshu)
            SkipRule(
                id = "builtin:com.xingin.xhs:splash",
                packageName = "com.xingin.xhs",
                appName = "小红书",
                description = "开屏广告跳过",
                targetId = "com.xingin.xhs:id/splash_skip_tv",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 腾讯视频 (Tencent Video)
            SkipRule(
                id = "builtin:com.tencent.qqlive:splash",
                packageName = "com.tencent.qqlive",
                appName = "腾讯视频",
                description = "开屏广告跳过",
                targetId = "com.tencent.qqlive:id/btn_splash_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 爱奇艺 (iQiyi)
            SkipRule(
                id = "builtin:com.qiyi.video:splash",
                packageName = "com.qiyi.video",
                appName = "爱奇艺",
                description = "开屏广告跳过",
                targetId = "com.qiyi.video:id/skip_ad_btn",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 优酷视频 (Youku)
            SkipRule(
                id = "builtin:com.youku.phone:splash",
                packageName = "com.youku.phone",
                appName = "优酷视频",
                description = "开屏广告跳过",
                targetId = "com.youku.phone:id/skip_view",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // QQ
            SkipRule(
                id = "builtin:com.tencent.mobileqq:splash",
                packageName = "com.tencent.mobileqq",
                appName = "QQ",
                description = "开屏广告跳过",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 夸克 (Quark)
            SkipRule(
                id = "builtin:com.quark.browser:splash",
                packageName = "com.quark.browser",
                appName = "夸克",
                description = "开屏广告跳过",
                targetId = "com.quark.browser:id/splash_skip_btn",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 喜马拉雅 (Ximalaya)
            SkipRule(
                id = "builtin:com.ximalaya.ting.android:splash",
                packageName = "com.ximalaya.ting.android",
                appName = "喜马拉雅",
                description = "开屏广告跳过",
                targetId = "com.ximalaya.ting.android:id/splash_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 今日头条 (Toutiao)
            SkipRule(
                id = "builtin:com.ss.android.article.news:splash",
                packageName = "com.ss.android.article.news",
                appName = "今日头条",
                description = "开屏广告跳过",
                targetId = "com.ss.android.article.news:id/tt_splash_skip_tv",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 懂车帝 (Dongchedi)
            SkipRule(
                id = "builtin:com.ss.android.auto:splash",
                packageName = "com.ss.android.auto",
                appName = "懂车帝",
                description = "开屏广告跳过",
                targetId = "com.ss.android.auto:id/skip_btn",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 豆瓣 (Douban)
            SkipRule(
                id = "builtin:com.douban.frodo:splash",
                packageName = "com.douban.frodo",
                appName = "豆瓣",
                description = "开屏广告跳过",
                targetId = "com.douban.frodo:id/skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 闲鱼 (Idlefish)
            SkipRule(
                id = "builtin:com.taobao.idlefish:splash",
                packageName = "com.taobao.idlefish",
                appName = "闲鱼",
                description = "开屏广告跳过",
                targetId = "com.taobao.idlefish:id/splash_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 百度地图 (Baidu Maps)
            SkipRule(
                id = "builtin:com.baidu.BaiduMap:splash",
                packageName = "com.baidu.BaiduMap",
                appName = "百度地图",
                description = "开屏广告跳过",
                targetId = "com.baidu.BaiduMap:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 饿了么 (Eleme)
            SkipRule(
                id = "builtin:me.ele:splash",
                packageName = "me.ele",
                appName = "饿了么",
                description = "开屏广告跳过",
                targetId = "me.ele:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 滴滴出行 (DiDi)
            SkipRule(
                id = "builtin:com.sdu.didi.psnger:splash",
                packageName = "com.sdu.didi.psnger",
                appName = "滴滴出行",
                description = "开屏广告跳过",
                targetId = "com.sdu.didi.psnger:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 网易新闻 (NetEase News)
            SkipRule(
                id = "builtin:com.netease.newsreader.activity:splash",
                packageName = "com.netease.newsreader.activity",
                appName = "网易新闻",
                description = "开屏广告跳过",
                targetId = "com.netease.newsreader.activity:id/splash_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 汽车之家 (Autohome)
            SkipRule(
                id = "builtin:com.autohome.android:splash",
                packageName = "com.autohome.android",
                appName = "汽车之家",
                description = "开屏广告跳过",
                targetId = "com.autohome.android:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 驾考宝典 (Jiakaobaodian)
            SkipRule(
                id = "builtin:com.handsgo.jiakao.android:splash",
                packageName = "com.handsgo.jiakao.android",
                appName = "驾考宝典",
                description = "开屏广告跳过",
                targetId = "com.handsgo.jiakao.android:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 虎扑 (Hupu)
            SkipRule(
                id = "builtin:com.hupu.games:splash",
                packageName = "com.hupu.games",
                appName = "虎扑",
                description = "开屏广告跳过",
                targetId = "com.hupu.games:id/btn_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 携程旅行 (Ctrip)
            SkipRule(
                id = "builtin:ctrip.android.view:splash",
                packageName = "ctrip.android.view",
                appName = "携程旅行",
                description = "开屏广告跳过",
                targetId = "ctrip.android.view:id/splash_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 去哪儿旅行 (Qunar)
            SkipRule(
                id = "builtin:com.Qunar:splash",
                packageName = "com.Qunar",
                appName = "去哪儿旅行",
                description = "开屏广告跳过",
                targetId = "com.Qunar:id/splash_skip",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            ),

            // 微信读书 (WeRead)
            SkipRule(
                id = "builtin:com.tencent.weread:splash",
                packageName = "com.tencent.weread",
                appName = "微信读书",
                description = "开屏跳过",
                targetTextRegex = "^(?:\\d+s?\\s*)?跳过(?:\\s*\\d+s?)?$",
                positionZone = SkipRule.PositionZone.TOP_ZONE
            )
        )
    }
}
