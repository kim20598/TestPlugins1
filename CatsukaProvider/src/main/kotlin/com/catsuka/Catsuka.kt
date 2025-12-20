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

    // Simple main pages
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
            
            // CATSUKA SPECIFIC SELECTORS - USE THESE
            val home = document.select(".swiper-slide, .item.video")
                .mapNotNull { it.toSearchResult() }
            
            return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = true)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // CATSUKA SPECIFIC: Get title from different locations
        val title = when {
            // For swiper slides with span
            this.selectFirst("span") != null -> {
                this.selectFirst("span")?.text()?.trim()
            }
            // For swiper slides with p (BINGE section)
            this.selectFirst("p") != null -> {
                this.selectFirst("p")?.text()?.trim()
            }
            // For .item.video in slider
            this.selectFirst(".caption span:first-child") != null -> {
                this.selectFirst(".caption span:first-child")?.text()?.trim()
            }
            // Fallback
            else -> {
                this.selectFirst("img")?.attr("alt")?.trim()
                    ?: this.attr("title")?.trim()
                    ?: this.attr("alt")?.trim()
            }
        } ?: return null
        
        if (title.isBlank()) return null
        
        // Get link
        val href = this.selectFirst("a")?.attr("href") ?: this.attr("href") ?: return null
        val fixedHref = fixUrl(href)
        if (!fixedHref.contains("/player/")) return null
        
        // Get thumbnail
        val posterUrl = this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("video")?.attr("poster")
        
        val fixedPoster = posterUrl?.let { 
            if (it.startsWith("http")) it else "$mainUrl$it" 
        }
        
        return newAnimeSearchResponse(title, fixedHref) {
            this.posterUrl = fixedPoster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.post(
                "$mainUrl/player/?recherche",
                data = mapOf("recherche" to query)
            ).document
            
            document.select(".swiper-slide, .item.video")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1.title, h1, .title, .caption span:first-child")?.text()?.trim() ?: "Unknown Title"
            val poster = document.selectFirst(".poster img, [itemprop=image], .cover img, img, video[poster]")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                ?: document.selectFirst("video")?.attr("poster")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val plot = document.selectFirst(".description, .plot, .summary, p, .caption span:nth-child(2)")?.text()?.trim()
            
            // Check if it's a movie
            val isMovie = true
            
            if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
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
}
