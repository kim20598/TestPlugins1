package com.letterboxd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.letterboxd.utils.ProviderRegistry
import com.letterboxd.utils.SettingsManager

@CloudstreamPlugin
class LetterboxdProvider : Plugin() {
    override fun load(context: Context) {
        // Initialize settings and provider registry
        SettingsManager.initialize(context)
        ProviderRegistry.initialize(context)
        
        // Register the main provider
        registerMainAPI(Letterboxd())
        
        // Optionally register settings
        openSettings = {
            // You could open a settings activity here
            // For now, we'll rely on Cloudstream's built-in settings
        }
    }
}