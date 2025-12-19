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
    
    // List of your other providers for user to choose from
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
        "profile_stats" to "My Stats",
        "trending" to "Trending Now",
        "popular_week" to "Popular This Week",
        "upcoming" to "Upcoming Films",
        "top_250" to "Top 250 Films"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // In real implementation, get username from settings
        val username = "exampleUser" // Placeholder
        
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
                val lists = fetchLists(username)
                val items = lists.map { list ->
                    newMovieSearchResponse(list.title, list.url, TvType.Movie) {
                        this.posterUrl = null
                        this.plot = "${list.filmCount} films"
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
            "top_250" -> {
                val items = fetchFromLetterboxd("$mainUrl/ajax/top-250-films/page/$page/")
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            else -> {
                // For profile_stats, show placeholder
                val items = listOf(
                    newMovieSearchResponse("Films Watched: 0", "", TvType.Movie),
                    newMovieSearchResponse("Watchlist: 0", "", TvType.Movie),
                    newMovieSearchResponse("Please set username in settings", "", TvType.Movie)
                )
                newHomePageResponse(request.name, items)
            }
        }
    }

    // ==================== SEARCH ====================
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/films/${URLEncoder.encode(query, "UTF-8")}/"
        val films = fetchFromLetterboxd(searchUrl)
        
        return films.map { film ->
            newMovieSearchResponse(film.title, film.url, TvType.Movie) {
                this.posterUrl = film.posterUrl
                this.year = film.year
                this.plot = film.description?.take(100)
                
                // Add provider availability hint
                if (availableProviders.isNotEmpty()) {
                    this.description = "Search across ${availableProviders.size} providers"
                }
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
        
        // Create a special data format that includes provider choices
        val dataString = buildString {
            append("letterboxd:$url")
            append("|providers:")
            append(availableProviders.joinToString(","))
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, dataString) {
            this.posterUrl = poster
            this.plot = buildString {
                description?.let { append(it) }
                append("\n\n")
                append("Available providers: ${availableProviders.joinToString(", ")}")
                append("\n\nSelect a provider to search for this film.")
            }
            this.year = year
            
            // Add recommendations
            val recommendations = fetchFromLetterboxd(url)
                .take(5)
                .map { rec ->
                    newMovieSearchResponse(rec.title, rec.url, TvType.Movie) {
                        this.posterUrl = rec.posterUrl
                    }
                }
            this.recommendations = recommendations
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
        val parts = data.split("|")
        val letterboxdUrl = parts.firstOrNull { it.startsWith("letterboxd:") }
            ?.removePrefix("letterboxd:") ?: return false
        
        val doc = app.get(letterboxdUrl).document
        val filmTitle = doc.selectFirst("meta[property='og:title']")?.attr("content") ?: "Unknown"
        val filmYear = doc.selectFirst(".releaseyear")?.text()?.toIntOrNull()
        
        // Extract providers from data or use default
        val providers = parts.firstOrNull { it.startsWith("providers:") }
            ?.removePrefix("providers:")
            ?.split(",")
            ?: availableProviders
        
        // Create a search bridge - this is the key innovation
        // Instead of directly loading links, we prepare search queries
        val searchQuery = if (filmYear != null) {
            "$filmTitle $filmYear"
        } else {
            filmTitle
        }
        
        // Store search info for the UI
        // In a real implementation, you would:
        // 1. Save the search query to shared preferences
        // 2. Launch a Cloudstream search activity
        // 3. Let user choose which provider to use
        
        // For now, return false since we're not directly loading links
        // This tells Cloudstream to show the "No links found" message
        // where we can add a custom button to trigger provider search
        
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
                    posterUrl = poster,
                    description = null
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
    
    // ==================== DATA CLASSES ====================
    
    data class LetterboxdFilm(
        val title: String,
        val year: Int? = null,
        val url: String,
        val posterUrl: String? = null,
        val description: String? = null
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
