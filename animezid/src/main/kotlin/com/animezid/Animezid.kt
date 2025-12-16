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

        // METHOD 1: Extract from server buttons with data-embed
        document.select("#xservers button[data-embed], .xservers button[data-embed], button[data-embed]").forEach { serverButton ->
            val embedUrl = serverButton.attr("data-embed").trim()
            val serverName = serverButton.text().trim().ifBlank { "Server" }
            
            if (embedUrl.isNotBlank()) {
                // These are direct embed URLs for various streaming hosts
                foundLinks = true
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        // METHOD 1b: Try to extract video ID and construct server URLs dynamically
        if (!foundLinks) {
            // Extract video ID from URL
            val videoId = extractVideoId(data)
            if (videoId != null) {
                // Try different server patterns for this video
                val serverPatterns = listOf(
                    "https://megamax.me/iframe/$videoId",
                    "https://zid.upns.online/#$videoId",
                    "https://zid.streamcasthub.store/#$videoId",
                    "https://zid.vidplayer.live/#$videoId",
                    "https://zid.rpmhub.site/#$videoId",
                    "https://dsvplay.com/e/$videoId",
                    "https://listeamed.net/e/$videoId",
                    "https://vidtube.pro/e/$videoId.html",
                    "https://uqload.cx/embed-$videoId.html"
                )
                
                for (serverUrl in serverPatterns) {
                    foundLinks = true
                    loadExtractor(serverUrl, data, subtitleCallback, callback)
                }
            }
        }

        // METHOD 2: Get the currently loaded iframe in Playerholder
        if (!foundLinks) {
            document.selectFirst("#Playerholder iframe")?.let { iframe ->
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            }
        }

        // METHOD 3: Extract download links (these are file hosting sites)
        document.select("a.dl.show_dl.api[href]").forEach { downloadLink ->
            val downloadUrl = downloadLink.attr("href").trim()
            val quality = downloadLink.select("span").firstOrNull()?.text() ?: "1080p"
            val host = downloadLink.select("span").getOrNull(1)?.text() ?: "Download"
            
            if (downloadUrl.isNotBlank() && downloadUrl.startsWith("http")) {
                foundLinks = true
                // Try to extract from file hosting sites
                when {
                    downloadUrl.contains("koramaup.com") ||
                    downloadUrl.contains("bowfile.com") ||
                    downloadUrl.contains("file-upload.org") ||
                    downloadUrl.contains("1fichier.com") ||
                    downloadUrl.contains("1cloudfile.com") ||
                    downloadUrl.contains("frdl.io") ||
                    downloadUrl.contains("lbx.to") -> {
                        // These are file hosting sites, might need special handling
                        // For now, try to load them with extractors
                        loadExtractor(downloadUrl, data, subtitleCallback, callback)
                    }
                }
            }
        }

        // METHOD 4: Fallback - check embed.php URL from meta tags
        if (!foundLinks) {
            document.selectFirst("meta[itemprop=embedURL]")?.attr("content")?.let { embedUrl ->
                if (embedUrl.isNotBlank() && embedUrl.contains("embed.php")) {
                    // Try to load the embed page
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
                    embedDoc.select("video source, source[src]").forEach { source ->
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
                }
            }
        }

        // METHOD 5: Check for video sources in the page itself
        if (!foundLinks) {
            document.select("video source[src], source[type^='video/']").forEach { source ->
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

    private fun extractVideoId(url: String): String? {
        // Try to extract from URL parameters
        val vidMatch = Regex("vid=([a-zA-Z0-9]+)").find(url)
        if (vidMatch != null) {
            return vidMatch.groupValues[1]
        }
        
        // Try to extract from embed URLs in the page
        val doc = app.get(url).document
        val embedUrl = doc.selectFirst("meta[itemprop=embedURL]")?.attr("content")
        
        if (embedUrl != null) {
            // Extract from embed URL
            val embedId = Regex("vid=([a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1)
            if (embedId != null) return embedId
            
            // Try other patterns
            val patterns = listOf(
                Regex("/([a-zA-Z0-9]+)$"),
                Regex("#([a-zA-Z0-9]+)$"),
                Regex("e/([a-zA-Z0-9]+)")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(embedUrl)
                if (match != null) return match.groupValues[1]
            }
        }
        
        // Try to get from server buttons
        val serverButton = doc.selectFirst("button[data-embed]")
        if (serverButton != null) {
            val embedAttr = serverButton.attr("data-embed")
            // Extract ID from embed URL
            val idPatterns = listOf(
                Regex("/([a-zA-Z0-9]+)$"),
                Regex("#([a-zA-Z0-9]+)$"),
                Regex("e/([a-zA-Z0-9]+)"),
                Regex("embed-([a-zA-Z0-9]+)")
            )
            
            for (pattern in idPatterns) {
                val match = pattern.find(embedAttr)
                if (match != null) return match.groupValues[1]
            }
        }
        
        return null
    }
}
