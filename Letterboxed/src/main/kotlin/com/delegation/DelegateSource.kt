package com.letterboxed.delegation

import com.letterboxed.model.MetaItem
import com.lagradost.cloudstream3.*

abstract class DelegateSource {
    abstract suspend fun searchAndLoad(
        meta: MetaItem,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    )
}
