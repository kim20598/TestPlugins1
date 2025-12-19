package com.letterboxd.utils

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.PluginManager
import kotlin.reflect.KClass

object ProviderRegistry {
    // List of all your available provider classes
    private val availableProviders = mapOf(
        "AnimeSuge" to "com.animesuge.provider.AnimeSuge",
        "Arabseed" to "com.arabseed.Arabseed",
        "Cineby" to "com.cineby.Cineby",
        "Akwam" to "com.akwam.Akwam",
        "Animezid" to "com.animezid.Animezid",
        "EgyDead" to "com.egydead.EgyDead"
    )
    
    private var activeProviders: Map<String, MainAPI> = emptyMap()
    
    fun initialize(context: Context) {
        activeProviders = availableProviders.mapNotNull { (name, className) ->
            try {
                val clazz = Class.forName(className)
                val provider = clazz.newInstance() as? MainAPI
                provider?.let { name to it }
            } catch (e: Exception) {
                null
            }
        }.toMap()
    }
    
    fun getProvider(name: String): MainAPI? {
        return activeProviders[name]
    }
    
    fun getAllProviders(): Map<String, MainAPI> {
        return activeProviders
    }
    
    fun getEnabledProviders(): List<MainAPI> {
        val enabledNames = SettingsManager.getEnabledProviders()
        return enabledNames.mapNotNull { getProvider(it) }
    }
    
    fun searchAcrossProviders(query: String): List<ProviderMatch> {
        val results = mutableListOf<ProviderMatch>()
        val enabledProviders = getEnabledProviders()
        
        enabledProviders.forEach { provider ->
            try {
                // This would need to be adapted based on your actual search mechanism
                // For now, this is a placeholder structure
                val matches = performProviderSearch(provider, query)
                results.addAll(matches)
            } catch (e: Exception) {
                // Log error but continue with other providers
            }
        }
        
        return results.sortedByDescending { it.confidence }
    }
    
    private fun performProviderSearch(provider: MainAPI, query: String): List<ProviderMatch> {
        // This is a simplified version - you'd need actual search implementation
        return emptyList()
    }
}