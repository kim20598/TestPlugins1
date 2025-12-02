package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KooraLite : MainAPI() {
    override var mainUrl = "https://koora-live.io"
    override var name = "KooraLite"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Live
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "الأفلام",
        "$mainUrl/series" to "المسلسلات",
        "$mainUrl/live" to "البث المباشر",
        "$mainUrl/latest" to "أحدث الإضافات",
        "$mainUrl/most-watched" to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url, headers = getHeaders()).document
        
        val items = document.select("div.video-item, article.item, .movie-item, .post").mapNotNull { element ->
            element.toSearchResponse()
        }
        
        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty() && document.select("a[rel='next'], .next-page").isNotEmpty()
        )
    }

    // ==================== SEARCH ====================
    
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?q=$encodedQuery"
        
        val document = app.get(searchUrl, headers = getHeaders()).document
        
        return document.select("div.video-item, article.item, .movie-item, .post").mapNotNull { element ->
            element.toSearchResponse()
        }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeaders()).document
        
        // Extract metadata
        val title = document.selectFirst("h1.entry-title, h1.title, .post-title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("meta[property='og:image'], .poster img, .thumbnail img")?.attr("content")
            ?: document.selectFirst("img[src*='poster'], img[src*='cover']")?.attr("src")
            ?: ""
        
        val description = document.selectFirst(".description, .plot, .synopsis, .content")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: ""
        
        val year = document.selectFirst(".year, .release-date, .date")?.text()?.findYear()
        
        // Check if it's a series by looking for episodes or seasons
        val hasEpisodes = document.select(".episodes-list, .seasons, .episode-item, a[href*='/episode/']").isNotEmpty()
        
        if (hasEpisodes) {
            // TV Series
            val episodes = extractEpisodes(document, url)
            
            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
                this.year = year
            }
        } else {
            // Movie or Live
            val isLive = url.contains("/live/") || title.contains("مباشر", true)
            val type = if (isLive) TvType.Live else TvType.Movie
            
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = type,
                dataUrl = url
            ) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
                this.year = year
            }
        }
    }

    private suspend fun extractEpisodes(document: Element, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Episodes list
        document.select(".episodes-list a, .episode-item a, a[href*='/episode/']").forEach { episodeLink ->
            val episodeUrl = episodeLink.attr("href").let { href ->
                if (href.startsWith("http")) href else fixUrl(href)
            }
            
            val episodeTitle = episodeLink.attr("title").ifBlank {
                episodeLink.select(".title, .episode-title").text().ifBlank {
                    episodeLink.text()
                }
            }
            
            val episodeNumber = episodeLink.attr("data-episode").toIntOrNull()
                ?: Regex("""(\d+)""").find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""حلقة\s*(\d+)""").find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            
            val seasonNumber = episodeLink.attr("data-season").toIntOrNull()
                ?: Regex("""موسم\s*(\d+)""").find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            
            if (episodeUrl.isNotBlank()) {
                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = episodeTitle.ifBlank { "الحلقة $episodeNumber" }
                        this.episode = episodeNumber
                        this.season = seasonNumber
                    }
                )
            }
        }
        
        // Method 2: Seasons structure
        if (episodes.isEmpty()) {
            document.select(".season, [class*='season']").forEachIndexed { seasonIndex, season ->
                val seasonNum = seasonIndex + 1
                
                season.select(".episode, a[href*='watch']").forEachIndexed { episodeIndex, episode ->
                    val episodeUrl = episode.attr("href").let { href ->
                        if (href.startsWith("http")) href else fixUrl(href)
                    }
                    
                    if (episodeUrl.isNotBlank()) {
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = "الحلقة ${episodeIndex + 1}"
                                this.episode = episodeIndex + 1
                                this.season = seasonNum
                            }
                        )
                    }
                }
            }
        }
        
        return episodes.distinctBy { it.url }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = getHeaders()).document
        var foundLinks = false
        
        // Method 1: Direct video sources
        document.select("video source, source[src]").forEach { source ->
            val videoUrl = source.attr("src").trim()
            if (videoUrl.isNotBlank()) {
                foundLinks = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(videoUrl),
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = getQualityFromName(source.attr("label") ?: source.attr("size"))
                    }
                )
            }
        }
        
        // Method 2: Iframe embeds
        document.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src").trim()
            if (iframeSrc.isNotBlank()) {
                foundLinks = true
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            }
        }
        
        // Method 3: Video.js players
        document.select("video[data-setup]").forEach { video ->
            val setupJson = video.attr("data-setup")
            if (setupJson.contains("sources")) {
                val sources = Regex("""sources\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATTERS)
                    .find(setupJson)?.groupValues?.get(1)
                
                sources?.let { srcJson ->
                    Regex("""src\s*:\s*["']([^"']+)["']""").findAll(srcJson).forEach { match ->
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.isNotBlank()) {
                            foundLinks = true
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = fixUrl(videoUrl),
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = data
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Method 4: Look for common Arabic streaming host patterns
        val arabicHosts = listOf(
            "youtube.com",
            "vimeo.com",
            "dailymotion.com",
            "ok.ru",
            "tune.pk",
            "streamable.com",
            "myvi.ru",
            "uptobox.com",
            "mega.nz"
        )
        
        document.select("a[href]").forEach { link ->
            val href = link.attr("href").trim()
            arabicHosts.forEach { host ->
                if (href.contains(host)) {
                    foundLinks = true
                    loadExtractor(fixUrl(href), data, subtitleCallback, callback)
                }
            }
        }
        
        // Method 5: Extract from JavaScript
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            
            // Look for m3u8 URLs
            Regex("""(https?://[^\s"']*\.m3u8[^\s"']*)""").findAll(scriptContent).forEach { match ->
                val m3u8Url = match.groupValues[1]
                if (m3u8Url.isNotBlank()) {
                    foundLinks = true
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name M3U8",
                            url = fixUrl(m3u8Url),
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = data
                        }
                    )
                }
            }
            
            // Look for mp4 URLs
            Regex("""(https?://[^\s"']*\.mp4[^\s"']*)""").findAll(scriptContent).forEach { match ->
                val mp4Url = match.groupValues[1]
                if (mp4Url.isNotBlank()) {
                    foundLinks = true
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name MP4",
                            url = fixUrl(mp4Url),
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
        
        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        // Try multiple selectors for title
        val titleElement = selectFirst(".title, h3, h2, .entry-title, .video-title")
        val title = titleElement?.text()?.trim() ?: attr("title").trim()
        
        if (title.isBlank()) return null
        
        // Get URL
        val href = attr("href").ifBlank { selectFirst("a")?.attr("href") ?: "" }
        if (href.isBlank()) return null
        
        // Get poster
        val poster = selectFirst("img")?.attr("src")?.ifBlank {
            selectFirst("img")?.attr("data-src")
        } ?: selectFirst(".poster, .thumbnail")?.attr("style")
            ?.let { Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1) }
            ?: ""
        
        // Determine type
        val url = fixUrl(href)
        val isMovie = url.contains("/movie/") || title.contains("فيلم", true)
        val isSeries = url.contains("/series/") || url.contains("/season/") || url.contains("/episode/")
        val isLive = url.contains("/live/") || title.contains("مباشر", true)
        
        val type = when {
            isSeries -> TvType.TvSeries
            isLive -> TvType.Live
            else -> TvType.Movie
        }
        
        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = fixUrl(poster)
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

    private fun String.findYear(): Int? {
        return Regex("""(19|20)\d{2}""").find(this)?.value?.toIntOrNull()
    }

    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )
    }
}
