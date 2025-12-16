package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder

class Animezid : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://animezid.cam"
    override var name = "Animezid"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    // ==================== MAIN PAGE ====================
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "أحدث الإضافات",
        "$mainUrl/category.php?cat=anime" to "الانمي",
        "$mainUrl/category.php?cat=movies" to "الافلام",
        "$mainUrl/category.php?cat=series" to "المسلسلات",
        "$mainUrl/category.php?cat=disney-masr" to "ديزني بالمصري",
        "$mainUrl/category.php?cat=spacetoon" to "سبيستون",
        "$mainUrl/topvideos.php" to "الأكثر مشاهدة"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageParam = if (page > 1) "&page=$page" else ""
        val url = if (request.data.contains("?")) {
            request.data + pageParam
        } else {
            request.data
        }
        
        val document = app.get(url).document
        val items = document.select("a.movie").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?keywords=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        return document.select("a.movie").mapNotNull { it.toSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title from multiple possible locations
        val title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1 span strong")?.text()
            ?: document.selectFirst("h1.post__name")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "Unknown"

        // Extract poster from meta tags or images
        val poster = document.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
            ?: document.selectFirst("img.lazy")?.attr("data-src")
            ?: ""
            
        // Extract description
        val description = document.selectFirst(".pm-video-description p.description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: ""
            
        // Check for episodes (seasons and episodes tabs)
        val hasSeasons = document.select(".tab-seasons li[data-serie]").isNotEmpty()
        val episodes = mutableListOf<Episode>()
        
        if (hasSeasons) {
            // This is a series with seasons
            document.select(".SeasonsEpisodes[data-serie]").forEach { seasonDiv ->
                val seasonNum = seasonDiv.attr("data-serie").toIntOrNull() ?: 1
                
                seasonDiv.select("a[href*='watch.php']").forEach { episodeLink ->
                    val episodeUrl = fixUrl(episodeLink.attr("href"))
                    val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                    val episodeTitle = episodeLink.select("span").text()
                        .ifBlank { "الحلقة $episodeNum" }
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.episode = episodeNum
                            this.season = seasonNum
                        }
                    )
                }
            }
        } else {
            // Check for direct episode links without seasons
            document.select("a[href*='watch.php?vid=']").forEach { episodeLink ->
                if (episodeLink.parents().select(".SeasonsEpisodes, .tab-episodes").isNotEmpty()) {
                    val episodeUrl = fixUrl(episodeLink.attr("href"))
                    val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                    val episodeTitle = episodeLink.select("span").text()
                        .ifBlank { "الحلقة $episodeNum" }
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.episode = episodeNum
                            this.season = 1
                        }
                    )
                }
            }
        }
        
        return if (episodes.isNotEmpty()) {
            // TV Series
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.distinctBy { "${it.season}_${it.episode}" }) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
            }
        } else {
            // Movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
            }
        }
    }

    // ==================== LOAD LINKS - COMPLETE WORKING VERSION ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // METHOD 1: Extract from server buttons with data-embed (Primary method)
        document.select("#xservers button[data-embed], .xservers button[data-embed], button[data-embed]").forEach { serverButton ->
            val embedUrl = serverButton.attr("data-embed").trim()
            val serverName = serverButton.text().trim().ifBlank { "Server" }
            
            if (embedUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        // METHOD 2: Try to get iframe directly from onclick attributes (Common pattern on this site)
        if (!foundLinks) {
            document.select("button[onclick*='iframe'], li[onclick*='iframe'], a[onclick*='iframe']").forEach { element ->
                val onclick = element.attr("onclick")
                // Extract URL from onclick like: loadVideo('https://example.com/embed/xxx')
                val urlPatterns = listOf(
                    Regex("loadVideo\\(['\"]([^'\"]+)['\"]\\)"),
                    Regex("changeVideo\\(['\"]([^'\"]+)['\"]\\)"),
                    Regex("['\"](https?://[^'\"]+)['\"]"),
                    Regex("src=['\"]([^'\"]+)['\"]")
                )
                
                for (pattern in urlPatterns) {
                    val match = pattern.find(onclick)
                    if (match != null) {
                        val extractedUrl = match.groupValues[1]
                        if (extractedUrl.isNotBlank()) {
                            foundLinks = true
                            loadExtractor(extractedUrl, data, subtitleCallback, callback)
                        }
                    }
                }
            }
        }

        // METHOD 3: Get the currently loaded iframe in Playerholder
        if (!foundLinks) {
            document.selectFirst("#Playerholder iframe, .player-iframe iframe, iframe[src]")?.let { iframe ->
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isNotBlank() && iframeSrc != "about:blank") {
                    foundLinks = true
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            }
        }

        // METHOD 4: Try to load embed.php from meta tags
        if (!foundLinks) {
            document.selectFirst("meta[itemprop=embedURL], meta[property='og:video:url']")?.attr("content")?.let { embedUrl ->
                if (embedUrl.isNotBlank() && embedUrl.contains("embed.php")) {
                    // Try to load the embed page
                    try {
                        val embedDoc = app.get(embedUrl).document
                        
                        // Look for video sources or iframes in the embed page
                        embedDoc.select("iframe[src]").forEach { iframe ->
                            val iframeSrc = iframe.attr("src")
                            if (iframeSrc.isNotBlank()) {
                                foundLinks = true
                                loadExtractor(fixUrl(iframeSrc), embedUrl, subtitleCallback, callback)
                            }
                        }
                        
                        // Check for direct video sources
                        embedDoc.select("video source[src], source[type^='video/'][src]").forEach { source ->
                            val videoUrl = source.attr("src")
                            if (videoUrl.isNotBlank()) {
                                foundLinks = true
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = name,
                                        url = fixUrl(videoUrl),
                                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = embedUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore errors and try next method
                    }
                }
            }
        }

        // METHOD 5: Extract download links (these are file hosting sites)
        document.select("a.dl.show_dl.api[href], a[href*='download'], .download-link[href]").forEach { downloadLink ->
            val downloadUrl = downloadLink.attr("href").trim()
            if (downloadUrl.isNotBlank() && downloadUrl.startsWith("http")) {
                foundLinks = true
                // Try to extract from file hosting sites
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
            }
        }

        // METHOD 6: Check for video sources in the page itself
        if (!foundLinks) {
            document.select("video source[src], source[type^='video/'][src]").forEach { source ->
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
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        // METHOD 7: Try to extract from script tags that contain video URLs
        if (!foundLinks) {
            document.select("script:containsData(http)").forEach { script ->
                val scriptContent = script.data()
                // Look for common video URL patterns in scripts
                val patterns = listOf(
                    Regex("""["'](https?://[^"']+\.(mp4|m3u8|mkv|avi|mov|wmv|flv)[^"']*)["']"""),
                    Regex("""src:\s*["'](https?://[^"']+)["']"""),
                    Regex("""file:\s*["'](https?://[^"']+)["']"""),
                    Regex("""url:\s*["'](https?://[^"']+)["']""")
                )
                
                for (pattern in patterns) {
                    val matches = pattern.findAll(scriptContent)
                    for (match in matches) {
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.isNotBlank() && !videoUrl.contains("google") && !videoUrl.contains("facebook")) {
                            foundLinks = true
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = fixUrl(videoUrl),
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            }
        }

        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.attr("title").trim()
            .ifBlank { this.selectFirst(".title")?.text()?.trim() }
            ?: return null
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        // Extract poster from lazy-loaded image
        val poster = this.selectFirst("img.lazy")?.attr("data-src")
            ?.ifBlank { this.selectFirst("img")?.attr("src") }
            ?: ""
        
        // Determine type based on URL or title
        val isMovie = title.contains("فيلم") || 
                     href.contains("/movie/") ||
                     !href.contains("/watch.php?vid=")

        return if (isMovie) {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
