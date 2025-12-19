package com.letterboxd.utils

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private lateinit var prefs: SharedPreferences
    private const val PREFS_NAME = "letterboxd_settings"
    
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    var letterboxdUsername: String?
        get() = prefs.getString("username", null)
        set(value) = prefs.edit().putString("username", value).apply()
    
    var autoSearchEnabled: Boolean
        get() = prefs.getBoolean("auto_search", true)
        set(value) = prefs.edit().putBoolean("auto_search", value).apply()
    
    var preferredLanguage: String
        get() = prefs.getString("language", "en") ?: "en"
        set(value) = prefs.edit().putString("language", value).apply()
    
    var preferredQuality: String
        get() = prefs.getString("quality", "1080p") ?: "1080p"
        set(value) = prefs.edit().putString("quality", value).apply()
    
    fun getEnabledProviders(): List<String> {
        return prefs.getStringSet("enabled_providers", setOf(
            "AnimeSuge",
            "Arabseed",
            "Cineby",
            "Akwam",
            "Animezid",
            "EgyDead"
        ))?.toList() ?: emptyList()
    }
    
    fun setEnabledProviders(providers: List<String>) {
        prefs.edit().putStringSet("enabled_providers", providers.toSet()).apply()
    }
    
    var cacheDuration: Int
        get() = prefs.getInt("cache_duration", 24) // hours
        set(value) = prefs.edit().putInt("cache_duration", value).apply()
    
    var showOnlyFreeContent: Boolean
        get() = prefs.getBoolean("free_only", true)
        set(value) = prefs.edit().putBoolean("free_only", value).apply()
    
    var maxResultsPerProvider: Int
        get() = prefs.getInt("max_results", 5)
        set(value) = prefs.edit().putInt("max_results", value).apply()
    
    fun clearSettings() {
        prefs.edit().clear().apply()
    }
}