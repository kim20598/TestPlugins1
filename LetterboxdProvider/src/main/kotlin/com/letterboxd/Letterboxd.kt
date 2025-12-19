package com.letterboxd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.letterboxd.models.*
import com.letterboxd.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class Letterboxd : MainAPI() {
    override var mainUrl = "https://letterboxd.com"
    override var name = "Letterboxd"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Documentary
    )
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    
    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "profile_watchlist" to "My Watchlist",
        "profile_watched" to "Recently Watched",
        "profile_lists" to "My Lists",
        "profile_stats" to "My Stats",
        "trending" to "Trending Now",
        "popular_this_week" to "Popular This Week",
        "upcoming" to "Upcoming Films",
        "top_250" to "Top 250 Narrative Features"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val username = SettingsManager.letterboxdUsername
        if (username.isNullOrEmpty() && request.data.startsWith("profile_")) {
            return newHomePageResponse(
                "Please set your Letterboxd username in settings",
                emptyList()
            )
        }
        
        return when (request.data) {
            "profile_watchlist" -> {
                val items = fetchWatchlist(username!!, page)
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "profile_watched" -> {
                val items = fetchWatched(username!!, page)
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "profile_lists" -> {
                val lists = fetchUserLists(username!!)
                val items = lists.map { list ->
                    newMovieSearchResponse(list.title, list.url, TvType.Movie) {
                        this.posterUrl = null
                        this.plot = "${list.filmCount} films • ${list.likesCount} likes"
                    }
                }
                newHomePageResponse(request.name, items)
            }
            "profile_stats" -> {
                val stats = fetchUserStats(username!!)
                val items = listOf(
                    newMovieSearchResponse("Films Watched: ${stats?.watched ?: 0}", "", TvType.Movie),
                    newMovieSearchResponse("Watchlist: ${stats?.watchlist ?: 0}", "", TvType.Movie),
                    newMovieSearchResponse("Reviews: ${stats?.reviews ?: 0}", "", TvType.Movie),
                    newMovieSearchResponse("Lists: ${stats?.lists ?: 0}", "", TvType.Movie)
                )
                newHomePageResponse(request.name, items)
            }
            "trending" -> {
                val items = fetchTrending(page)
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "popular_this_week" -> {
                val items = fetchPopularThisWeek(page)
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "upcoming" -> {
                val items = fetchUpcoming(page)
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            "top_250" -> {
                val items = fetchTop250(page)
                    .map { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
            else -> throw ErrorLoadingException()
        }
    }
    
    // ==================== SEARCH ====================
    
    override suspend fun search(query: String): List<SearchResponse> {
        // Search Letterboxd
        val searchUrl = "$mainUrl/search/films/${URLEncoder.encode(query, "UTF-8")}/"
        
        val films = try {
            fetchFilmsFromPage(searchUrl)
        } catch (e: Exception) {
            emptyList()
        }
        
        return films.map { film ->
            val matches = if (SettingsManager.autoSearchEnabled) {
                SearchAggregator.findProviderMatches(film)
            } else {
                emptyList()
            }
            
            newMovieSearchResponse(film.title, film.letterboxdUrl, TvType.Movie) {
                this.posterUrl = film.posterUrl
                this.year = film.year
                this.plot = if (matches.isNotEmpty()) {
                    "Available on: ${matches.take(3).joinToString(", ") { it.providerName }}"
                } else {
                    film.description?.take(100)
                }
            }
        }
    }
    
    // ==================== LOAD ====================
    
    override suspend fun load(url: String): LoadResponse {
        val film = fetchFilmDetails(url)
        
        // Search for provider matches
        val providerMatches = if (SettingsManager.autoSearchEnabled) {
            SearchAggregator.findProviderMatches(film)
        } else {
            emptyList()
        }
        
        // Create a special data string that includes both Letterboxd URL and provider matches
        val dataString = buildString {
            append("letterboxd:$url")
            if (providerMatches.isNotEmpty()) {
                append("|providers:")
                append(providerMatches.joinToString(",") { 
                    "${it.providerName}:${it.matchUrl}"
                })
            }
        }
        
        return newMovieLoadResponse(film.title, url, TvType.Movie, dataString) {
            this.posterUrl = film.posterUrl
            this.backgroundPosterUrl = film.backdropUrl
            this.plot = buildString {
                film.description?.let { append(it) }
                append("\n\n")
                if (film.director != null) append("Director: ${film.director}\n")
                if (film.cast.isNotEmpty()) append("Cast: ${film.cast.take(5).joinToString(", ")}\n")
                if (film.genres.isNotEmpty()) append("Genres: ${film.genres.joinToString(", ")}\n")
                if (film.runtime != null) append("Runtime: ${film.runtime} min\n")
                film.rating?.let { append("Letterboxd Rating: ${String.format("%.1f", it)}/5\n") }
                
                if (providerMatches.isNotEmpty()) {
                    append("\n🎬 Available on:\n")
                    providerMatches.take(5).forEach { match ->
                        append("• ${match.providerName}")
                        match.quality?.let { append(" ($it)") }
                        append("\n")
                    }
                    if (providerMatches.size > 5) append("... and ${providerMatches.size - 5} more")
                } else if (SettingsManager.autoSearchEnabled) {
                    append("\n🔍 No streaming sources found automatically")
                }
            }
            this.year = film.year
            this.tags = film.genres
            
            // Add recommendations
            val recommendations = fetchSimilarFilms(url)
            this.recommendations = recommendations.map { rec ->
                newMovieSearchResponse(rec.title, rec.letterboxdUrl, TvType.Movie) {
                    this.posterUrl = rec.posterUrl
                }
            }
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
        val letterboxdUrl = parts.firstOrNull { it.startsWith("letterboxd:") }?.removePrefix("letterboxd:")
            ?: return false
        
        val film = fetchFilmDetails(letterboxdUrl)
        
        // Get provider URLs from data or search fresh
        val providerUrls = if (parts.any { it.startsWith("providers:") }) {
            parts.first { it.startsWith("providers:") }
                .removePrefix("providers:")
                .split(",")
                .map { providerPair ->
                    val (providerName, url) = providerPair.split(":", limit = 2)
                    providerName to url
                }
        } else {
            // Search for providers
            SearchAggregator.findProviderMatches(film)
                .map { it.providerName to it.matchUrl }
        }
        
        if (providerUrls.isEmpty()) {
            return false
        }
        
        // Load links from each provider concurrently
        val deferredResults = providerUrls.map { (providerName, url) ->
            CoroutineScope(Dispatchers.IO).async {
                try {
                    val provider = ProviderRegistry.getProvider(providerName)
                    if (provider != null) {
                        var linksFound = 0
                        provider.loadLinks(url, isCasting, subtitleCallback) { link ->
                            // Modify link source to include provider name
                            callback(
                                ExtractorLink(
                                    source = "Letterboxd • ${provider.name}",
                                    name = link.name,
                                    url = link.url,
                                    referer = link.referer,
                                    quality = link.quality,
                                    type = link.type,
                                    headers = link.headers,
                                    extras = link.extras
                                )
                            )
                            linksFound++
                        }
                        linksFound > 0
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }
        
        val results = deferredResults.awaitAll()
        return results.any { it }
    }
    
    // ==================== LETTERBOXD API METHODS ====================
    
    private suspend fun fetchFilmDetails(url: String): LetterboxdFilm {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content") ?: "Unknown"
        val year = doc.selectFirst(".releaseyear")?.text()?.toIntOrNull()
        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        
        val backdrop = doc.selectFirst(".backdrop img")?.attr("src")
            ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
        
        val rating = doc.selectFirst(".rating")?.text()?.toFloatOrNull()
        val director = doc.select("a[href*='/director/']").firstOrNull()?.text()
        val cast = doc.select("a[href*='/actor/']").map { it.text() }.take(10)
        val genres = doc.select("a[href*='/films/genre/']").map { it.text() }
        
        val runtimeText = doc.selectFirst("p.runtime")?.text()
        val runtime = runtimeText?.let { 
            Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        // Try to get external IDs
        val tmdbLink = doc.select("a[href*='themoviedb.org']").firstOrNull()
        val tmdbId = tmdbLink?.attr("href")?.substringAfterLast("/")?.toIntOrNull()
        
        val imdbLink = doc.select("a[href*='imdb.com']").firstOrNull()
        val imdbId = imdbLink?.attr("href")?.substringAfter("title/")?.substringBefore("/")
        
        return LetterboxdFilm(
            title = title,
            year = year,
            letterboxdUrl = url,
            posterUrl = poster,
            backdropUrl = backdrop,
            description = description,
            rating = rating,
            director = director,
            cast = cast,
            genres = genres,
            runtime = runtime,
            tmdbId = tmdbId,
            imdbId = imdbId
        )
    }
    
    private suspend fun fetchWatchlist(username: String, page: Int): List<LetterboxdFilm> {
        val url = "$mainUrl/$username/watchlist/page/$page/"
        return fetchFilmsFromPage(url)
    }
    
    private suspend fun fetchWatched(username: String, page: Int): List<LetterboxdFilm> {
        val url = "$mainUrl/$username/films/page/$page/"
        return fetchFilmsFromPage(url)
    }
    
    private suspend fun fetchUserLists(username: String): List<LetterboxdList> {
        val url = "$mainUrl/$username/lists/"
        val doc = app.get(url).document
        
        return doc.select("ul.poster-list li").mapNotNull { listItem ->
            val title = listItem.selectFirst("h2")?.text() ?: return@mapNotNull null
            val link = listItem.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val description = listItem.selectFirst(".body-text")?.text()
            val filmCount = listItem.selectFirst(".filmcount")?.text()?.toIntOrNull() ?: 0
            val likesCount = listItem.selectFirst(".likecount")?.text()?.toIntOrNull() ?: 0
            val commentsCount = listItem.selectFirst(".commentcount")?.text()?.toIntOrNull() ?: 0
            
            LetterboxdList(
                title = title,
                url = "$mainUrl$link",
                description = description,
                filmCount = filmCount,
                likesCount = likesCount,
                commentsCount = commentsCount
            )
        }
    }
    
    private suspend fun fetchUserStats(username: String): UserStats? {
        val url = "$mainUrl/$username/"
        val doc = app.get(url).document
        
        return try {
            val watched = doc.select("a[href*='/films/'] span").firstOrNull()?.text()?.toIntOrNull() ?: 0
            val watchlist = doc.select("a[href*='/watchlist/'] span").firstOrNull()?.text()?.toIntOrNull() ?: 0
            val reviews = doc.select("a[href*='/reviews/'] span").firstOrNull()?.text()?.toIntOrNull() ?: 0
            val lists = doc.select("a[href*='/lists/'] span").firstOrNull()?.text()?.toIntOrNull() ?: 0
            val likes = doc.select("a[href*='/likes/'] span").firstOrNull()?.text()?.toIntOrNull() ?: 0
            
            UserStats(
                watched = watched,
                watchlist = watchlist,
                reviews = reviews,
                lists = lists,
                likes = likes
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun fetchTrending(page: Int): List<LetterboxdFilm> {
        val url = "$mainUrl/ajax/popular/this/week/films/page/$page/"
        return fetchFilmsFromPage(url)
    }
    
    private suspend fun fetchPopularThisWeek(page: Int): List<LetterboxdFilm> {
        val url = "$mainUrl/ajax/popular/this/week/films/page/$page/"
        return fetchFilmsFromPage(url)
    }
    
    private suspend fun fetchUpcoming(page: Int): List<LetterboxdFilm> {
        val url = "$mainUrl/films/upcoming/page/$page/"
        return fetchFilmsFromPage(url)
    }
    
    private suspend fun fetchTop250(page: Int): List<LetterboxdFilm> {
        val url = "$mainUrl/ajax/top-250-films/page/$page/"
        return fetchFilmsFromPage(url)
    }
    
    private suspend fun fetchFilmsFromPage(url: String): List<LetterboxdFilm> {
        val doc = app.get(url).document
        
        return doc.select("li.poster-container").mapNotNull { item ->
            val title = item.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val poster = item.selectFirst("img")?.attr("src")
            val link = item.selectFirst("a")?.attr("href")
            val rating = item.selectFirst(".rating")?.text()?.trim()
            val yearText = item.selectFirst(".filmdate")?.text()
            val year = yearText?.toIntOrNull()
            
            LetterboxdFilm(
                title = title,
                year = year,
                letterboxdUrl = link?.let { "$mainUrl$it" } ?: "",
                posterUrl = poster,
                rating = rating?.toFloatOrNull()
            )
        }
    }
    
    private suspend fun fetchSimilarFilms(url: String): List<LetterboxdFilm> {
        val similarUrl = url.replace("/film/", "/film/") // Actual similar films endpoint
        return try {
            fetchFilmsFromPage(similarUrl)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // ==================== HELPER EXTENSIONS ====================
    
    private fun LetterboxdFilm.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(title, letterboxdUrl, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = description?.take(100)
        }
    }
}