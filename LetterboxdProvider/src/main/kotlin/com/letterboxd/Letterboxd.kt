package com.letterboxd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class Letterboxd : MainAPI() {
    override var mainUrl = "https://letterboxd.com"
    override var name = "Letterboxd Sync"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Documentary
    )
    
    // List of your other providers for user reference
    private val availableProviders = listOf(
        "AnimeSuge",
        "Arabseed", 
        "Cineby",
        "Akwam",
        "Animezid",
        "EgyDead"
    )

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "profile_watchlist" to "My Watchlist",
        "profile_watched" to "Recently Watched",
        "profile_lists" to "My Lists",
        "trending" to "Trending Now",
        "popular_week" to "Popular This Week",
        "upcoming" to "Upcoming Films"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // For demo - in real app, get from settings
        val username = "exampleUser"
        
        return when (request.data) {
            "profile_watchlist" -> {
                val items = fetchFromLetterboxd("$mainUrl/$username/watchlist/page/$page/")
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "profile_watched" -> {
                val items = fetchFromLetterboxd("$mainUrl/$username/films/page/$page/")
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "profile_lists" -> {
                val lists = try {
                    fetchLists(username)
                } catch (e: Exception) {
                    emptyList()
                }
                val items = lists.map { list ->
                    newMovieSearchResponse(list.title, list.url, TvType.Movie) {
                        this.posterUrl = null
                    }
                }
                newHomePageResponse(request.name, items)
            }
            "trending" -> {
                val items = fetchFromLetterboxd("$mainUrl/ajax/popular/this/week/films/page/$page/")
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "popular_week" -> {
                val items = fetchFromLetterboxd("$mainUrl/ajax/popular/this/week/films/page/$page/")
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "upcoming" -> {
                val items = fetchFromLetterboxd("$mainUrl/films/upcoming/page/$page/")
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            else -> {
                // Fallback
                newHomePageResponse(request.name, emptyList())
            }
        }
    }

    // ==================== SEARCH ====================
    
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "-")
        val searchUrl = "$mainUrl/search/films/$encodedQuery/"
        
        val films = try {
            fetchFromLetterboxd(searchUrl)
        } catch (e: Exception) {
            emptyList()
        }
        
        return films.map { film ->
            newMovieSearchResponse(film.title, film.url, TvType.Movie) {
                this.posterUrl = film.posterUrl
                this.year = film.year
            }
        }
    }

    // ==================== LOAD ====================
    
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Extract film details from Letterboxd
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content") ?: "Unknown"
        val year = doc.selectFirst(".releaseyear")?.text()?.toIntOrNull()
        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        
        // Store the film title for search reference
        val dataString = "letterboxd:$title:$year"
        
        return newMovieLoadResponse(title, url, TvType.Movie, dataString) {
            this.posterUrl = poster
            this.plot = buildString {
                description?.let { append(it) }
                append("\n\n")
                append("This is a Letterboxd integration provider.")
                append("\n\nAvailable providers to search:")
                availableProviders.forEach { provider ->
                    append("\n• $provider")
                }
                append("\n\nSearch for this film in any provider using the title: '$title'")
            }
            this.year = year
        }
    }

    // ==================== LOAD LINKS ====================
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Parse the data string
        val parts = data.removePrefix("letterboxd:").split(":")
        if (parts.size < 2) return false
        
        val filmTitle = parts[0]
        val filmYear = parts.getOrNull(1)?.toIntOrNull()
        
        // For Letterboxd provider, we don't directly provide links
        // Instead, we suggest searching in other providers
        // Return false to indicate no direct links available
        // The user will see a message and can manually search
        
        // You could add a custom message here, but the simplest approach
        // is to return false and let the user search manually
        
        return false
    }

    // ==================== HELPER FUNCTIONS ====================
    
    private suspend fun fetchFromLetterboxd(url: String): List<LetterboxdFilm> {
        return try {
            val doc = app.get(url).document
            doc.select("li.poster-container").mapNotNull { item ->
                val title = item.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val poster = item.selectFirst("img")?.attr("src")
                val link = item.selectFirst("a")?.attr("href")
                val yearText = item.selectFirst(".filmdate")?.text()
                val year = yearText?.toIntOrNull()
                
                LetterboxdFilm(
                    title = title,
                    year = year,
                    url = link?.let { "$mainUrl$it" } ?: "",
                    posterUrl = poster
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private suspend fun fetchLists(username: String): List<LetterboxdList> {
        return try {
            val url = "$mainUrl/$username/lists/"
            val doc = app.get(url).document
            
            doc.select("ul.poster-list li").mapNotNull { item ->
                val title = item.selectFirst("h2")?.text() ?: return@mapNotNull null
                val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val filmCount = item.selectFirst(".filmcount")?.text()?.toIntOrNull() ?: 0
                
                LetterboxdList(
                    title = title,
                    url = "$mainUrl$link",
                    filmCount = filmCount
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // ==================== SIMPLE DATA CLASSES ====================
    
    data class LetterboxdFilm(
        val title: String,
        val year: Int? = null,
        val url: String,
        val posterUrl: String? = null
    ) {
        fun toSearchResponse(): SearchResponse {
            return newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }
    
    data class LetterboxdList(
        val title: String,
        val url: String,
        val filmCount: Int = 0
    )
}
