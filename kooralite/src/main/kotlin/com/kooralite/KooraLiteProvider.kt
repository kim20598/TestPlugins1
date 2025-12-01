package com.kooralite

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KooraLiteProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KooraLite())
    }
}