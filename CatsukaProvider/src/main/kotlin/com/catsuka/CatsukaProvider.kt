package com.catsuka.provider

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CatsukaProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Catsuka())
        registerExtractorAPI(VimeoExtractor())
    }
}