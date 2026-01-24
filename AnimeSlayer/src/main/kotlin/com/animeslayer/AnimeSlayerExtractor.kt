package com.animeslayer.provider

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor

class AnimeSlayerExtractor : ExtractorApi() {
    override val name = "أنمي سلاير استخراج"
    override val mainUrl = "https://animeslayerweb.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // For now, just pass through to generic extractor
            // The main extraction is handled in AnimeSlayer.kt
            loadExtractor(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            // Fallback
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
}
