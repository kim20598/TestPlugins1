package com.catsuka

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CatsukaProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CatsukaPlayer())
        // You can register extractors here if needed
        // registerExtractorAPI(SomeExtractor())
    }
}