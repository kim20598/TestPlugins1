package com.animesuge.provider

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeSugePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeSuge())
        registerExtractorAPI(AnimeSugeMegaPlay())
        // Remove these if you don't have them:
        // registerExtractorAPI(AnimeSugeStreamWish())
        // registerExtractorAPI(AnimeSugeFileMoon())
    }
}
