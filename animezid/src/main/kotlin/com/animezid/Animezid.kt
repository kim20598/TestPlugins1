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
            ?: document.selectFirst("img[itemprop=image]")?.attr("src")
            ?: ""
            
        // Extract description - clean it up
        val rawDescription = document.selectFirst(".pm-video-description p.description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: ""
        
        // Clean description
        val description = cleanDescriptionText(rawDescription)
            
        // Check for episodes (seasons and episodes tabs)
        val episodes = mutableListOf<Episode>()
        
        // Check if this is actually a series with episodes
        val hasSeasonsTab = document.select(".tab-seasons li[data-serie]").isNotEmpty()
        val hasEpisodesTab = document.select(".tab-episodes").isNotEmpty()
        val hasSeasonsEpisodes = document.select(".SeasonsEpisodes").isNotEmpty()
        
        // Only extract episodes if this is clearly a series
        if (hasSeasonsTab || hasEpisodesTab || hasSeasonsEpisodes) {
            // METHOD 1: Check for seasons and episodes in the proper structure
            if (hasSeasonsTab) {
                // This is a series with seasons
                document.select(".SeasonsEpisodes[data-serie]").forEach { seasonDiv ->
                    val seasonNum = seasonDiv.attr("data-serie").toIntOrNull() ?: 1
                    
                    seasonDiv.select("a[href*='watch.php']").forEach { episodeLink ->
                        val episodeUrl = fixUrl(episodeLink.attr("href"))
                        val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                        val episodeTitle = cleanEpisodeTitle(episodeLink.select("span").text(), episodeNum)
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = episodeTitle
                                this.episode = episodeNum
                                this.season = seasonNum
                            }
                        )
                    }
                }
            } else if (hasEpisodesTab) {
                // METHOD 2: Check for episodes without seasons (direct episodes list)
                document.select(".tab-episodes .SeasonsEpisodes a[href*='watch.php']").forEach { episodeLink ->
                    val episodeUrl = fixUrl(episodeLink.attr("href"))
                    val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                    val episodeTitle = cleanEpisodeTitle(episodeLink.select("span").text(), episodeNum)
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.episode = episodeNum
                            this.season = 1
                        }
                    )
                }
            }
            
            // METHOD 3: Fallback - any clear episode links
            if (episodes.isEmpty() && hasSeasonsEpisodes) {
                document.select(".SeasonsEpisodes a[href*='watch.php']").forEach { episodeLink ->
                    val episodeUrl = fixUrl(episodeLink.attr("href"))
                    val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                    val episodeTitle = cleanEpisodeTitle(episodeLink.select("span").text(), episodeNum)
                    
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
        
        // Better detection for movie vs series
        val isSeries = when {
            // If we found real episodes (not just one episode), it's a series
            episodes.size > 1 -> true
            // If there are seasons tabs with multiple seasons
            document.select(".tab-seasons li[data-serie]").size > 1 -> true
            // If there are multiple episodes in tabs
            document.select(".SeasonsEpisodes a[href*='watch.php']").size > 1 -> true
            // If title contains series indicators
            cleanTitle.contains("مسلسل") || 
            cleanTitle.contains("الموسم") || 
            cleanTitle.contains("الحلقة") ||
            cleanTitle.contains("الجزء") -> true
            // If description contains series indicators
            (description?.contains("مسلسل") == true || 
             description?.contains("الموسم") == true || 
             description?.contains("الحلقة") == true) -> true
            // Default to movie (most content on the site is movies)
            else -> false
        }
        
        return if (isSeries && episodes.isNotEmpty()) {
            // TV Series
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodes.distinctBy { "${it.season}_${it.episode}" }) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
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
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
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
            // Title contains movie indicators (Arabic)
            cleanTitle.contains("فيلم") ||
            cleanTitle.contains("فلم") ||
            cleanTitle.contains("الفيلم") -> true
            // Title contains movie indicators (English)
            cleanTitle.contains("Movie", ignoreCase = true) ||
            cleanTitle.contains("Film", ignoreCase = true) -> true
            // URL contains movie indicators
            href.contains("/movie/") || href.contains("/movies/") -> true
            // Check ribbon for movie indicators
            this.select(".ribbon").text().contains("فيلم") ||
            this.select(".ribbon").text().contains("فلم") ||
            this.select(".ribbon").text().contains("WEB-DL") ||
            this.select(".ribbon").text().contains("BluRay") -> true
            // Default to series (anime) if it looks like series
            cleanTitle.contains("الموسم") ||
            cleanTitle.contains("الحلقة") ||
            cleanTitle.contains("مسلسل") ||
            cleanTitle.contains("الجزء") -> false
            // Most content on animezid is movies, so default to movie
            else -> true
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
            // Remove common prefixes
            .replace("فيلم\\s*".toRegex(), "")
            .replace("فلم\\s*".toRegex(), "")
            .replace("مسلسل\\s*".toRegex(), "")
            .replace("\\|.*".toRegex(), "") // Remove everything after |
            .replace("\\s*مدبلج.*".toRegex(), "")
            .replace("\\s*مترجم.*".toRegex(), "")
            .replace("\\s*بالعربية.*".toRegex(), "")
            .replace("\\s*بالمصري.*".toRegex(), "")
            .replace("\\s*مدبلج مصري.*".toRegex(), "")
            .replace("\\s*مدبلج بالعربية.*".toRegex(), "")
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

    private fun cleanEpisodeTitle(text: String, episodeNum: Int): String {
        val cleaned = text
            .replace("الحلقة\\s*".toRegex(), "")
            .trim()
        
        return if (cleaned.isBlank() || cleaned == "الحلقة") {
            "الحلقة $episodeNum"
        } else {
            cleaned
        }
    }
}
