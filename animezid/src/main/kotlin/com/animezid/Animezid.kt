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
        
        // Extract title - clean up welcome messages
        val rawTitle = document.selectFirst("h1")?.text()
            ?: document.selectFirst("h1.post__name")?.text()
            ?: document.selectFirst(".video-title")?.text()
            ?: document.selectFirst(".post__title")?.text()
            ?: "Unknown"

        // Clean the title - remove welcome messages and site names
        val cleanTitle = cleanTitleText(rawTitle)
            
        // Extract poster
        val poster = document.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
            ?: document.selectFirst("img.lazy")?.attr("data-src")
            ?: document.selectFirst(".post__image img")?.attr("src")
            ?: ""
            
        // Extract description
        val description = document.selectFirst(".pm-video-description p.description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst(".post__description")?.text()?.trim()
            ?: ""
        
        val cleanDescription = cleanDescriptionText(description)
            
        // Check for episodes (seasons and episodes tabs)
        val episodes = mutableListOf<Episode>()
        
        // METHOD 1: Check for seasons and episodes in the proper structure
        val hasSeasons = document.select(".tab-seasons li[data-serie]").isNotEmpty()
        
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
            // METHOD 2: Check for episodes without seasons (direct episodes list)
            document.select(".tab-episodes .SeasonsEpisodes a[href*='watch.php']").forEach { episodeLink ->
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
            
            // METHOD 3: Check for any watch.php links that might be episodes
            if (episodes.isEmpty()) {
                document.select("a[href*='watch.php?vid=']").forEach { episodeLink ->
                    // Skip if it's the current video or not in an episodes section
                    if (!episodeLink.attr("href").contains(url.substringAfterLast("vid=")) &&
                        (episodeLink.parents().select(".SeasonsEpisodes, .tab-episodes, .pm-video-watch-episodes").isNotEmpty() ||
                         episodeLink.text().contains("الحلقة") || 
                         episodeLink.select("em").isNotEmpty())) {
                        
                        val episodeUrl = fixUrl(episodeLink.attr("href"))
                        val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                        val episodeTitle = episodeLink.text().trim()
                            .ifBlank { "الحلقة ${episodeLink.select("em").text()}" }
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
        }
        
        // Better detection for movie vs series - FIXED FOR MOVIES
        val isSeries = when {
            // If we found episodes, it's a series
            episodes.isNotEmpty() -> true
            // If there are seasons tabs, it's a series
            document.select(".tab-seasons").isNotEmpty() -> true
            // If there are episodes tabs, it's a series
            document.select(".tab-episodes").isNotEmpty() -> true
            // If title contains series indicators
            rawTitle.contains("مسلسل") || 
            rawTitle.contains("الموسم") || 
            rawTitle.contains("الحلقة") ||
            rawTitle.contains("الموسم") ||
            rawTitle.contains("الحلقة") -> true
            // If URL indicates series
            url.contains("/category.php?cat=series") ||
            url.contains("/category.php?cat=anime") -> true
            // If URL indicates a movie category - treat as movie
            url.contains("/category.php?cat=movies") ||
            url.contains("/movie/") -> false
            // Default - check for single episode (like in your screenshot)
            else -> {
                // If there's only one download link or it looks like a movie page
                val hasDownloadLinks = document.select("a.dl.show_dl.api[href]").isNotEmpty()
                val hasSingleEpisode = document.select("a[href*='watch.php']").size == 1
                
                // If it has download links but no episode structure, it's likely a movie
                hasDownloadLinks && !hasSingleEpisode
            }
        }
        
        // For movies, we need to pass the correct URL to loadLinks
        val loadData = if (isSeries && episodes.isNotEmpty()) {
            // For series, use the first episode URL for testing
            episodes.firstOrNull()?.data ?: url
        } else {
            // For movies, use the current page URL
            url
        }
        
        return if (isSeries) {
            // TV Series
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodes.distinctBy { "${it.season}_${it.episode}" }) {
                this.posterUrl = fixUrl(poster)
                this.plot = cleanDescription
            }
        } else {
            // Movie
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, loadData) {
                this.posterUrl = fixUrl(poster)
                this.plot = cleanDescription
            }
        }
    }

    // ==================== LOAD LINKS - KEEP ORIGINAL WORKING VERSION ====================

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
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
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
        
        // Better detection for movie vs series in search results
        val isMovie = when {
            // Title contains movie indicators
            rawTitle.contains("فيلم") || rawTitle.contains("فلم") -> true
            // URL contains movie indicators
            href.contains("/movie/") || href.contains("/movies/") -> true
            // Search result has specific classes or attributes for movies
            this.hasClass("movie-film") || this.select(".ribbon").text().contains("فيلم") -> true
            // If it doesn't look like an episode link
            !href.contains("/watch.php?vid=") -> true
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
            .replace("مرحباً في موقع انمي زد الأصلي", "")
            .replace("مرحباً في موقع انمي زد الاصلي", "")
            .replace("مرحباً في موقع انمي زد", "")
            .replace("انمي زد الأصلي", "")
            .replace("انمي زد الاصلي", "")
            .replace("Animezid", "")
            .replace("قبام زوتروبويس", "زوتروبويس") // Example from screenshot
            // Remove "title:" prefix and similar
            .replace("^title\\s*[:\\.]\\s*".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("^عنوان\\s*[:\\.]\\s*".toRegex(), "")
            .replace("^اسم\\s*[:\\.]\\s*".toRegex(), "")
            // Remove common Arabic prefixes
            .replace("^فيلم\\s+".toRegex(), "")
            .replace("^فلم\\s+".toRegex(), "")
            .replace("^مسلسل\\s+".toRegex(), "")
            // Remove quality info after |
            .replace("\\s*\\|.*".toRegex(), "")
            // Remove dubbing info
            .replace("\\s*مدبلج.*".toRegex(), "")
            .replace("\\s*مترجم.*".toRegex(), "")
            .replace("\\s*بالعربية.*".toRegex(), "")
            .replace("\\s*بالمصري.*".toRegex(), "")
            .replace("\\s*مدبلع.*".toRegex(), "") // From screenshot
            // Clean extra spaces
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun cleanDescriptionText(text: String): String? {
        val cleaned = text
            // Remove welcome messages
            .replace("مرحباً في موقع انمي زد الأصلي", "")
            .replace("مرحباً في موقع انمي زد الاصلي", "")
            .replace("مرحباً في موقع انمي زد", "")
            .replace("انمي زد الأصلي", "")
            .replace("انمي زد الاصلي", "")
            // Clean extra spaces
            .replace("\\s+".toRegex(), " ")
            .trim()
        
        return cleaned.ifBlank { null }
    }
}
