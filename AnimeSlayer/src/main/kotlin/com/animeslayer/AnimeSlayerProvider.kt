package com.animeslayer

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSlayerProvider : Plugin() {
    override fun load() {
        registerMainAPI(AnimeSlayer())
    }
}
