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

    // ==================== LOAD - COMPLETELY REWRITTEN ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title from multiple possible locations
        val rawTitle = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1 span strong")?.text()
            ?: document.selectFirst("h1.post__name")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: ""

        // Clean the title - remove prefixes and unwanted text
        val cleanTitle = cleanTitleText(rawTitle)

        // Extract poster from meta tags or images
        val poster = document.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
            ?: document.selectFirst("img.lazy")?.attr("data-src")
            ?: ""
            
        // Extract description - clean it up
        val rawDescription = document.selectFirst(".pm-video-description p.description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: ""
        
        // Clean description
        val description = cleanDescriptionText(rawDescription)
            
        // Check if this is a series page with multiple episodes/seasons
        val hasSeasonsTabs = document.select(".tab-seasons li[data-serie]").isNotEmpty()
        val hasSeasonsEpisodes = document.select(".SeasonsEpisodes").isNotEmpty()
        
        // Extract all episodes if this is a series
        val episodes = mutableListOf<Episode>()
        
        if (hasSeasonsTabs || hasSeasonsEpisodes) {
            // This is a series page with multiple episodes
            document.select(".SeasonsEpisodes[data-serie]").forEach { seasonDiv ->
                val seasonNum = seasonDiv.attr("data-serie").toIntOrNull() ?: 1
                
                seasonDiv.select("a[href*='watch.php']").forEach { episodeLink ->
                    val episodeUrl = fixUrl(episodeLink.attr("href"))
                    val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                    
                    // Get episode title from span or use default
                    val rawEpisodeTitle = episodeLink.select("span").text().trim()
                    val episodeTitle = if (rawEpisodeTitle.isNotBlank() && rawEpisodeTitle != "الحلقة") {
                        rawEpisodeTitle
                    } else {
                        "الحلقة $episodeNum"
                    }
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.episode = episodeNum
                            this.season = seasonNum
                        }
                    )
                }
            }
        }
        
        // Determine if this is a series or movie
        val isSeries = when {
            // If we found multiple episodes, it's definitely a series
            episodes.size > 1 -> true
            // If there are seasons tabs, it's a series
            hasSeasonsTabs -> true
            // If there are seasons episodes divs, it's a series
            hasSeasonsEpisodes -> true
            // If title contains series indicators (but not "فيلم")
            (cleanTitle.contains("الموسم") || 
             cleanTitle.contains("الحلقة") ||
             cleanTitle.contains("الجزء")) && 
             !cleanTitle.contains("فيلم") -> true
            // If description contains series indicators
            description?.contains("الموسم") == true || 
            description?.contains("الحلقة") == true ||
            description?.contains("مسلسل") == true -> true
            // Default to movie
            else -> false
        }
        
        // Get the current episode number if this is an individual episode page
        val currentEpisodeNum = extractEpisodeNumberFromTitle(cleanTitle)
        val currentSeasonNum = extractSeasonNumberFromTitle(cleanTitle)
        
        return if (isSeries) {
            // TV Series - need to determine the series title without episode info
            val seriesTitle = extractSeriesTitle(cleanTitle)
            
            // If this is an individual episode page but we found other episodes
            if (episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(seriesTitle, url, TvType.Anime, episodes.distinctBy { "${it.season}_${it.episode}" }) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = description
                }
            } else {
                // Individual episode treated as a single-episode series
                newTvSeriesLoadResponse(seriesTitle, url, TvType.Anime, listOf(
                    newEpisode(url) {
                        this.name = cleanTitle
                        this.episode = currentEpisodeNum
                        this.season = currentSeasonNum
                    }
                )) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = description
                }
            }
        } else {
            // Movie
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
            }
        }
    }

    // ==================== LOAD LINKS - FIXED VERSION ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // METHOD 1: Extract from server buttons with data-embed (THE CORRECT WAY)
        document.select("#xservers button[data-embed]").forEach { serverButton ->
            val embedUrl = serverButton.attr("data-embed").trim()
            val serverName = serverButton.text().trim().ifBlank { "Server" }
            
            if (embedUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        // METHOD 2: Get the currently loaded iframe in Playerholder (active server)
        if (!foundLinks) {
            document.selectFirst("#Playerholder iframe[src]")?.let { iframe ->
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isNotBlank() && iframeSrc != "about:blank") {
                    foundLinks = true
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            }
        }

        // METHOD 3: Extract download links (these are file hosting sites)
        document.select("a.dl.show_dl.api[href]").forEach { downloadLink ->
            val downloadUrl = downloadLink.attr("href").trim()
            val qualityText = downloadLink.select("span").firstOrNull()?.text() ?: "Unknown"
            val host = downloadLink.select("span").getOrNull(1)?.text() ?: "Download"
            
            if (downloadUrl.isNotBlank() && downloadUrl.startsWith("http")) {
                foundLinks = true
                
                // For file hosting sites, try to load them with extractors
                when {
                    downloadUrl.contains("koramaup.com") ||
                    downloadUrl.contains("bowfile.com") ||
                    downloadUrl.contains("file-upload.org") ||
                    downloadUrl.contains("1fichier.com") ||
                    downloadUrl.contains("1cloudfile.com") ||
                    downloadUrl.contains("frdl.io") ||
                    downloadUrl.contains("lbx.to") -> {
                        loadExtractor(downloadUrl, data, subtitleCallback, callback)
                    }
                    else -> {
                        // For other download links, try extractor first
                        loadExtractor(downloadUrl, data, subtitleCallback, callback)
                    }
                }
            }
        }

        // METHOD 4: Fallback - try the embed.php URL from meta tags
        if (!foundLinks) {
            document.selectFirst("meta[itemprop=embedURL]")?.attr("content")?.let { embedUrl ->
                if (embedUrl.isNotBlank() && embedUrl.contains("embed.php")) {
                    foundLinks = true
                    // Try to extract from embed page
                    try {
                        val embedDoc = app.get(embedUrl).document
                        
                        // Look for iframes in the embed page
                        embedDoc.select("iframe[src]").forEach { iframe ->
                            val iframeSrc = iframe.attr("src")
                            if (iframeSrc.isNotBlank()) {
                                loadExtractor(fixUrl(iframeSrc), embedUrl, subtitleCallback, callback)
                            }
                        }
                        
                        // Check for direct video sources
                        embedDoc.select("video source[src], source[type^='video/'][src]").forEach { source ->
                            val videoUrl = source.attr("src")
                            if (videoUrl.isNotBlank()) {
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
                        // If embed page fails, try the embed URL itself
                        loadExtractor(embedUrl, data, subtitleCallback, callback)
                    }
                }
            }
        }

        // METHOD 5: Check for direct video sources on the page
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

        return foundLinks
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val rawTitle = this.attr("title").trim()
            .ifBlank { this.selectFirst(".title")?.text()?.trim() }
            ?: return null
        
        // Clean the title
        val cleanTitle = cleanTitleText(rawTitle)
            .ifBlank { return null }
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        // Extract poster from lazy-loaded image
        val poster = this.selectFirst("img.lazy")?.attr("data-src")
            ?.ifBlank { this.selectFirst("img")?.attr("src") }
            ?: ""
        
        // Determine type based on URL or title
        val isMovie = when {
            // Title contains movie indicators
            cleanTitle.contains("فيلم") || cleanTitle.contains("فلم") -> true
            // Check ribbon for movie indicators
            this.select(".ribbon").text().contains("فيلم") ||
            this.select(".ribbon").text().contains("فلم") ||
            this.select(".ribbon").text().contains("WEB-DL") ||
            this.select(".ribbon").text().contains("BluRay") -> true
            // Title contains series indicators
            cleanTitle.contains("الحلقة") || 
            cleanTitle.contains("الموسم") ||
            cleanTitle.contains("الجزء") -> false
            // URL contains movie indicators
            href.contains("/movie/") || href.contains("/movies/") -> true
            // Default to series (anime)
            else -> false
        }

        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
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

    private fun cleanTitleText(text: String): String {
        return text
            // Remove welcome messages
            .replace("مرحباً في موقع", "")
            .replace("انمي زد الاصلي", "")
            .replace("انمي زد الأصل", "")
            .replace("مرحباً في موقع انمي زد الأصل", "")
            .replace("مرحباً في موقع انمي زد الاصلي", "")
            .replace("\\s+".toRegex(), " ") // Replace multiple spaces with single space
            .trim()
            .ifBlank { text.trim() } // Return original if cleaned is empty
    }

    private fun cleanDescriptionText(text: String): String? {
        val cleaned = text
            .replace("مرحباً في موقع", "")
            .replace("انمي زد الاصلي", "")
            .replace("انمي زد الأصل", "")
            .replace("مرحباً في موقع انمي زد الأصل", "")
            .replace("مرحباً في موقع انمي زد الاصلي", "")
            .trim()
        
        return cleaned.ifBlank { null }
    }

    private fun extractEpisodeNumberFromTitle(title: String): Int {
        // Try to extract episode number from title like "الحلقة 1184"
        val episodeRegex = Regex("الحلقة\\s*(\\d+)")
        val match = episodeRegex.find(title)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private fun extractSeasonNumberFromTitle(title: String): Int {
        // Try to extract season number from title like "الجزء 25" or "الموسم 25"
        val seasonRegex = Regex("(الجزء|الموسم)\\s*(\\d+)")
        val match = seasonRegex.find(title)
        return match?.groupValues?.get(2)?.toIntOrNull() ?: 1
    }

    private fun extractSeriesTitle(title: String): String {
        // Extract series title by removing episode/season info
        return title
            .replace(Regex("الحلقة\\s*\\d+"), "")
            .replace(Regex("الجزء\\s*\\d+"), "")
            .replace(Regex("الموسم\\s*\\d+"), "")
            .replace("مدبلجة", "")
            .replace("مدبلج", "")
            .replace("مترجمة", "")
            .replace("مترجم", "")
            .trim()
            .ifBlank { title }
    }
}
