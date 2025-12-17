package com.animesuge.provider

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeSugePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeSuge())
        // Register extractors
        registerExtractorAPI(AnimeSugeMegaPlay())
        registerExtractorAPI(AnimeSugeStreamWish())
        registerExtractorAPI(AnimeSugeFileMoon())
    }
}
