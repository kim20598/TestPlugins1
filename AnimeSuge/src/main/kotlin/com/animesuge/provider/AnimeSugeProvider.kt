package com.animesuge.provider

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSugeProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeSuge())
    }
}