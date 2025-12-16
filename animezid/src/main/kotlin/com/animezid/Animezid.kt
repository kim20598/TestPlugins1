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
        
        // ===== EXTRACT REAL TITLE =====
        // The real title is usually in an h1 tag or specific meta tags
        val cleanTitle = extractRealTitle(document)
            
        // Extract poster
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img.lazy")?.attr("data-src")
            ?: document.selectFirst("img[itemprop=image]")?.attr("src")
            ?: ""
            
        // Extract description - clean up welcome messages
        val description = document.selectFirst(".pm-video-description p")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: ""
        
        val cleanDescription = if (description.contains("مرحباً في موقع انمي زد")) {
            null
        } else {
            description
        }
        
        // Check for episodes (series)
        val episodes = mutableListOf<Episode>()
        
        // Try to find episodes from multiple possible locations
        // Look for season tabs
        document.select(".tab-seasons .SeasonsEpisodes").forEach { seasonDiv ->
            val seasonId = seasonDiv.attr("data-serie").toIntOrNull() ?: 1
            seasonDiv.select("a[href*='watch.php']").forEach { episodeLink ->
                val episodeUrl = fixUrl(episodeLink.attr("href"))
                val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                val episodeTitle = episodeLink.select("span").text().trim().ifBlank { "الحلقة $episodeNum" }
                
                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.episode = episodeNum
                        this.season = seasonId
                    }
                )
            }
        }
        
        // If no seasons found, check for direct episodes
        if (episodes.isEmpty()) {
            document.select("a[href*='watch.php']").forEach { episodeLink ->
                // Make sure it's an episode link, not some other link
                val href = episodeLink.attr("href")
                if (href.contains("watch.php") && episodeLink.select("em").isNotEmpty()) {
                    val episodeUrl = fixUrl(href)
                    val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                    val episodeTitle = episodeLink.select("span").text().trim().ifBlank { "الحلقة $episodeNum" }
                    
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
        
        // Determine if it's a movie or series
        val isSeries = when {
            episodes.size > 1 -> true
            // Check for series indicators in URL or content
            url.contains("series") || url.contains("anime") -> true
            // Check title for series indicators
            cleanTitle.contains("الموسم") || cleanTitle.contains("الحلقة") -> true
            // If we found any episode links at all, treat as series
            episodes.isNotEmpty() -> true
            else -> false
        }
        
        return if (isSeries && episodes.isNotEmpty()) {
            // TV Series
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodes.distinctBy { "${it.season}_${it.episode}" }) {
                this.posterUrl = fixUrl(poster)
                this.plot = cleanDescription
            }
        } else {
            // Movie
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = cleanDescription
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

        // For MOVIES: Check for direct video sources
        // Look for video tag first (direct video)
        document.select("video source[src]").forEach { source ->
            val videoUrl = source.attr("src")
            val quality = source.attr("data-quality") ?: "Unknown"
            
            if (videoUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        data,
                        Qualities.Unknown.value,
                        false
                    )
                )
                foundLinks = true
            }
        }

        // Check for iframe sources (embedded players)
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && src != "about:blank") {
                try {
                    loadExtractor(src, data, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Check for server buttons (common pattern on anime sites)
        document.select("button[data-embed], a[data-embed]").forEach { button ->
            val embedUrl = button.attr("data-embed").trim()
            if (embedUrl.isNotBlank()) {
                try {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Check for download links
        document.select("a[href*='download'], a[href*='watch.php']").forEach { link ->
            val href = link.attr("href").trim()
            val text = link.text().trim()
            
            if (href.isNotBlank() && (text.contains("مشاهدة") || text.contains("تحميل") || href.contains("watch.php"))) {
                try {
                    // For watch.php links, we need to get the actual video page
                    if (href.contains("watch.php")) {
                        val watchUrl = fixUrl(href)
                        val watchDoc = app.get(watchUrl).document
                        
                        // Try all extraction methods on the watch page
                        watchDoc.select("video source[src]").forEach { source ->
                            val videoUrl = source.attr("src")
                            if (videoUrl.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        name,
                                        "Direct Video",
                                        fixUrl(videoUrl),
                                        watchUrl,
                                        Qualities.Unknown.value,
                                        false
                                    )
                                )
                                foundLinks = true
                            }
                        }
                        
                        // Check for iframes in watch page
                        watchDoc.select("iframe[src]").forEach { iframe ->
                            val src = iframe.attr("src").trim()
                            if (src.isNotBlank()) {
                                try {
                                    loadExtractor(src, watchUrl, subtitleCallback, callback)
                                    foundLinks = true
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    } else {
                        // Direct download link
                        callback(
                            newExtractorLink(
                                name,
                                "Download - $text",
                                fixUrl(href),
                                data,
                                Qualities.Unknown.value,
                                false
                            )
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
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
        
        // Clean the title
        val cleanTitle = cleanTitleText(title)
            .ifBlank { return null }
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img.lazy")?.attr("data-src")
            ?: ""

        // Simple detection for movie vs series
        val isMovie = when {
            href.contains("/movie/") || href.contains("movies") -> true
            title.contains("فيلم") || title.contains("فلم") -> true
            this.select(".ribbon").text().contains("فيلم") -> true
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

    private fun extractRealTitle(document: org.jsoup.nodes.Document): String {
        // Try multiple strategies to get the real title
        
        // Strategy 1: Look for specific h1 structures
        val h1Text = document.selectFirst("h1")?.text()?.trim() ?: ""
        if (h1Text.isNotBlank() && !h1Text.contains("مرحباً في موقع")) {
            return cleanTitleText(h1Text)
        }
        
        // Strategy 2: Look for breadcrumb navigation (often contains real title)
        document.select(".breadcrumb a").lastOrNull()?.let { breadcrumb ->
            val breadcrumbText = breadcrumb.text().trim()
            if (breadcrumbText.isNotBlank() && !breadcrumbText.contains("مرحباً")) {
                return cleanTitleText(breadcrumbText)
            }
        }
        
        // Strategy 3: Look for meta tags
        val metaTitle = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name='title']")?.attr("content")?.trim()
        
        if (metaTitle != null && metaTitle.isNotBlank() && !metaTitle.contains("مرحباً في موقع")) {
            return cleanTitleText(metaTitle)
        }
        
        // Strategy 4: Look for specific content containers
        document.select(".post__name, .video-title, .movie-title, .entry-title").forEach { element ->
            val text = element.text().trim()
            if (text.isNotBlank() && !text.contains("مرحباً في موقع")) {
                return cleanTitleText(text)
            }
        }
        
        // Strategy 5: Last resort - get title from URL or use fallback
        val urlTitle = document.location()
            ?.substringAfterLast("/")
            ?.substringBefore("?")
            ?.replace("-", " ")
            ?.replace("_", " ")
            ?.trim()
            ?: ""
        
        return if (urlTitle.isNotBlank()) {
            cleanTitleText(urlTitle)
        } else {
            "Unknown Title"
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
            // Clean extra spaces
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
