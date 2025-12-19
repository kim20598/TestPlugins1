package com.delegation

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

abstract class DelegateSource {
    abstract suspend fun searchAndLoad(
        meta: MetaItem,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    )
}
