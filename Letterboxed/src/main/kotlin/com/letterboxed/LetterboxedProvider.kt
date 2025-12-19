package com.letterboxed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LetterboxedProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Letterboxed())
    }
}
