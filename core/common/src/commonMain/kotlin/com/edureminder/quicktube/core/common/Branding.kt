package com.edureminder.quicktube.core.common

/** What QuickTube writes into saved files' description / tags. File names carry no branding. */
object Branding {
    const val APP = "QuickTube"
    const val DOMAIN = "edureminder.com"
    const val URL = "https://edureminder.com"

    const val DESCRIPTION = "Downloaded with $APP by edureminder · $URL"
    const val ENCODER = "$APP ($DOMAIN)"

    val KEYWORDS = listOf(
        APP, "edureminder", DOMAIN, "QuickTube downloader", "video downloader", "YouTube downloader", "YouTube to MP3",
        "YouTube to MP4", "Facebook video downloader", "Instagram reels downloader", "TikTok downloader without watermark",
        "4K video downloader", "HD video downloader", "music downloader", "MP3 converter", "offline video player",
        "free video downloader", "status saver", "edureminder QuickTube",
    )
}
