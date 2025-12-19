package com.delegation

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

class AkwamDelegate : DelegateSource() {
    override suspend fun searchAndLoad(
        meta: MetaItem,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Your implementation here
        val url = "..." // Get URL from meta
        loadExtractor(url, subtitleCallback, callback)
    }
}
