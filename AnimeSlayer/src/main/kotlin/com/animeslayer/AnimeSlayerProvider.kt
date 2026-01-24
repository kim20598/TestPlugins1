package com.animeslayer.provider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSlayerProvider : Plugin() {
    override fun load() {
        // Register main AnimeSlayer provider
        registerMainAPI(AnimeSlayer())
        
        // Register extractor for video sources
        registerExtractorAPI(AnimeSlayerExtractor())
        
        // Note: Additional extractors can be registered here for specific video hosts
        // that Anime Slayer uses (like Streamtape, Voe, etc.)
    }
}