package com.letterboxd.utils

import com.letterboxd.models.LetterboxdFilm
import com.letterboxd.models.ProviderMatch
import com.letterboxd.models.UnifiedSearchResult
import java.text.Normalizer
import kotlin.math.min

object SearchAggregator {
    
    fun findProviderMatches(film: LetterboxdFilm): List<ProviderMatch> {
        val query = generateSearchQueries(film)
        val allMatches = mutableListOf<ProviderMatch>()
        
        query.forEach { searchQuery ->
            val matches = ProviderRegistry.searchAcrossProviders(searchQuery)
            allMatches.addAll(matches)
        }
        
        return allMatches.distinctBy { it.matchUrl }
    }
    
    private fun generateSearchQueries(film: LetterboxdFilm): List<String> {
        val queries = mutableListOf<String>()
        
        // Try different search query variations
        val baseTitle = normalizeTitle(film.title)
        queries.add(baseTitle)
        
        // With year
        film.year?.let {
            queries.add("$baseTitle $it")
        }
        
        // Try alternative titles (could be fetched from TMDB/IMDB)
        if (film.tmdbId != null || film.imdbId != null) {
            // You could fetch alternative titles here
        }
        
        // Try without special characters
        val cleanTitle = baseTitle.replace(Regex("[^\\w\\s]"), "")
        if (cleanTitle != baseTitle) {
            queries.add(cleanTitle)
        }
        
        // Try director + title for specific films
        film.director?.let { director ->
            queries.add("$director $baseTitle")
        }
        
        return queries.distinct()
    }
    
    private fun normalizeTitle(title: String): String {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("[^\\p{ASCII}]"), "")
            .trim()
    }
    
    fun calculateMatchConfidence(film: LetterboxdFilm, providerTitle: String): Float {
        val filmTitle = normalizeTitle(film.title)
        val matchTitle = normalizeTitle(providerTitle)
        
        // Simple Levenshtein distance for now
        val distance = levenshteinDistance(filmTitle, matchTitle)
        val maxLength = maxOf(filmTitle.length, matchTitle.length)
        
        var confidence = 1.0f - (distance.toFloat() / maxLength)
        
        // Boost confidence for exact matches
        if (filmTitle.equals(matchTitle, ignoreCase = true)) {
            confidence = 1.0f
        }
        
        // Check for year match if available
        film.year?.let { year ->
            if (matchTitle.contains(year.toString())) {
                confidence += 0.1f
            }
        }
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1].equals(s2[j - 1], ignoreCase = true)) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return dp[m][n]
    }
}