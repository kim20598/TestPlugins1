package com.catsuka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CatsukaPlayer : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka Player"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.Cartoon
    )

    // Player page specifically
    private val playerUrl = "$mainUrl/player"

    override val mainPage = mainPageOf(
        playerUrl to "Catsuka Player - All Content"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(playerUrl).document
        
        val items = document.select("a[href*='/player/']").mapNotNull { element ->
            element.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank() || href == playerUrl) return null
        
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        
        // Extract title from text content
        val titleText = text().trim()
        if (titleText.isBlank()) return null
        
        // Parse title and language info
        val (cleanTitle, language, type) = parseTitleInfo(titleText)
        
        // Determine TV type
        val tvType = when {
            type.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            type.contains("Seasons", ignoreCase = true) -> TvType.Anime
            else -> TvType.Cartoon
        }
        
        return newMovieSearchResponse(cleanTitle, fullUrl, tvType) {
            this.posterUrl = getDefaultPoster(tvType)
            // Add language as description
            this.description = "Language: $language | Type: $type"
        }
    }

    private fun parseTitleInfo(fullTitle: String): Triple<String, String, String> {
        // Example: "Mind Game - Movie (RU)" or "Mob Psycho 100 - Seasons (JP sub EN)"
        val parts = fullTitle.split(" - ")
        val title = parts.firstOrNull()?.trim() ?: fullTitle
        
        val typeAndLang = parts.getOrNull(1) ?: ""
        val type = if (typeAndLang.contains("Movie")) "Movie" 
                  else if (typeAndLang.contains("Seasons")) "Seasons" 
                  else ""
        
        // Extract language code from parentheses
        val langMatch = Regex("\\((.*?)\\)").find(typeAndLang)
        val language = langMatch?.groupValues?.get(1) ?: ""
        
        return Triple(title, language, type)
    }

    private fun getDefaultPoster(type: TvType): String? {
        // Return a default poster based on content type
        return when (type) {
            TvType.AnimeMovie -> "https://via.placeholder.com/300x450/FF6B6B/FFFFFF?text=Anime+Movie"
            TvType.Anime -> "https://via.placeholder.com/300x450/4ECDC4/FFFFFF?text=Anime+Series"
            TvType.Cartoon -> "https://via.placeholder.com/300x450/45B7D1/FFFFFF?text=Cartoon"
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Catsuka player doesn't have search, so return main page content filtered
        val document = app.get(playerUrl).document
        
        return document.select("a[href*='/player/']").mapNotNull { element ->
            val title = element.text().trim()
            if (title.contains(query, ignoreCase = true)) {
                element.toSearchResponse()
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
        val titleElement = document.selectFirst("h1, h2, .title, h3")
        val title = titleElement?.text()?.trim() ?: "Catsuka Animation"
        
        // Try to extract description
        val description = document.selectFirst("meta[name=description], .description, p")
            ?.attr("content")?.ifBlank { document.selectFirst("p")?.text() } 
            ?: "Animation work from Catsuka Player - Independent animation showcase"
        
        // Check if it's a movie or series
        val isMovie = title.contains("Movie", ignoreCase = true) || 
                     url.contains("movie", ignoreCase = true)
        
        // Look for video elements or iframes
        val videoElement = document.selectFirst("video, iframe[src*='player'], [data-video-src]")
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = getDefaultPoster(TvType.AnimeMovie)
                this.plot = description
                this.year = extractYearFromTitle(title)
            }
        } else {
            // For series, create a single episode pointing to the page itself
            val episodes = listOf(
                newEpisode(url) {
                    this.name = "Play"
                    this.episode = 1
                    this.season = 1
                }
            )
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = getDefaultPoster(TvType.Anime)
                this.plot = description
                this.year = extractYearFromTitle(title)
            }
        }
    }

    private fun extractYearFromTitle(title: String): Int? {
        val yearMatch = Regex("(19|20)\\d{2}").find(title)
        return yearMatch?.value?.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false
        
        // Method 1: Look for video elements
        document.select("video source[src], video[src]").forEach { source ->
            val videoUrl = source.attr("src").ifBlank { source.attr("data-src") }
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct Video",
                        url = fixUrl(videoUrl)
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                        this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                                   else ExtractorLinkType.VIDEO
                    }
                )
            }
        }
        
        // Method 2: Look for iframes
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }
        
        // Method 3: Look for embedded video scripts
        document.select("script").forEach { script ->
            val scriptText = script.html()
            val patterns = listOf(
                Regex("""src\s*:\s*['"](https?://[^"']+)['"]"""),
                Regex("""file\s*:\s*['"](https?://[^"']+)['"]"""),
                Regex("""(https?://[^\s"']*\.(mp4|m3u8|webm)[^\s"']*)""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && 
                        (videoUrl.contains("video") || videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                        foundLinks = true
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Embedded Video",
                                url = fixUrl(videoUrl)
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                                this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                                           else ExtractorLinkType.VIDEO
                            }
                        )
                    }
                }
            }
        }
        
        // Method 4: Check for common video hosting patterns
        if (!foundLinks) {
            val commonHosts = listOf("youtube.com", "vimeo.com", "dailymotion.com", "bitchute.com")
            document.select("a[href]").forEach { link ->
                val href = link.attr("href")
                if (commonHosts.any { host -> href.contains(host) }) {
                    foundLinks = true
                    loadExtractor(fixUrl(href), data, subtitleCallback, callback)
                }
            }
        }
        
        return foundLinks
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }
}