package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka Player"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // Copy AnimeSuge pattern EXACTLY
    override val mainPage = mainPageOf(
        "$mainUrl/player/" to "All Videos",
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/updates/" to "Updates"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val url = request.data + if (page > 1) "?page=$page" else ""
            val document = app.get(url).document
            
            // Copy AnimeSuge's selector pattern
            val home = document.select(".item, .anime-card, .card, .anime-poster, .poster, article, a")
                .mapNotNull { it.toSearchResult() }
            
            return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = true)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Look for title in multiple places like AnimeSuge does
        val title = this.selectFirst(".name, .detail .name, h3, h4, img[alt], span")?.text()?.trim() 
            ?: this.attr("title")
            ?: this.attr("alt")
            ?: return null
            
        if (title.isBlank()) return null
        
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: this.attr("href") ?: return null)
        if (!href.contains("/player/")) return null
        
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("data-src")
        )?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            // Catsuka uses POST search with "recherche" parameter
            val document = app.post(
                "$mainUrl/player/?recherche",
                data = mapOf("recherche" to query)
            ).document
            
            document.select(".item, .anime-card, .card, .anime-poster, .poster, article, a")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1.title, h1, .title")?.text()?.trim() ?: "Unknown Title"
            val poster = document.selectFirst(".poster img, [itemprop=image], .cover img, img")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val plot = document.selectFirst(".description, .plot, .summary, p")?.text()?.trim()
            
            // Check if it's a movie
            val isMovie = true // Assume movie for now
            
            if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // For series, create dummy episodes
                val episodes = (1..10).map { episodeNum ->
                    newEpisode(url) {
                        name = "Episode $episodeNum"
                        this.episode = episodeNum
                    }
                }
                
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.plot = "Failed to load: ${e.message}"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // If data is a URL
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // Look for iframe
                val iframe = document.selectFirst("iframe[src]")
                val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                
                if (iframeSrc != null && loadExtractor(iframeSrc, subtitleCallback, callback)) {
                    return true
                }
                
                // Look for video scripts
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // Look for Vimeo
                    val vimeoPattern = Regex("""vimeo\.com/(\d+)""")
                    val vimeoMatch = vimeoPattern.find(scriptText)
                    if (vimeoMatch != null) {
                        val videoId = vimeoMatch.groupValues[1]
                        val vimeoUrl = "https://player.vimeo.com/video/$videoId"
                        if (loadExtractor(vimeoUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                    
                    // Look for YouTube
                    val youtubePattern = Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""")
                    val youtubeMatch = youtubePattern.find(scriptText)
                    if (youtubeMatch != null) {
                        val videoId = youtubeMatch.groupValues[1]
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        if (loadExtractor(youtubeUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}
