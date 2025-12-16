package com.animezid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
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

    // ==================== LOAD - CLEAN VERSION ====================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title
        val rawTitle = document.selectFirst("h1.post__name")?.text()
            ?: document.selectFirst("h1 span strong")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: ""

        val title = cleanTitleText(rawTitle)
        
        // Extract poster
        val poster = document.selectFirst("meta[itemprop=image]")?.attr("content")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img.lazy")?.attr("data-src")
            ?: ""
            
        // Extract description
        val description = document.selectFirst(".pm-video-description p.description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: ""
        
        // Check if this is a series with seasons tabs
        val seasonsTabs = document.select(".tab-seasons li[data-serie]")
        
        // Check if this page has multiple episodes in SeasonsEpisodes
        val hasMultipleEpisodes = document.select(".SeasonsEpisodes").isNotEmpty()
        
        // Check for movie indicators
        val isMovie = title.contains("فيلم") || 
                     title.contains("فلم") ||
                     document.select(".ribbon").any { it.text().contains("فيلم") || it.text().contains("فلم") } ||
                     url.contains("/movie/") ||
                     !hasMultipleEpisodes && seasonsTabs.isEmpty() && !title.contains("الحلقة")
        
        return if (isMovie) {
            // MOVIE
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster.fixUrl()
                this.plot = description
            }
        } else {
            // TV SERIES
            // Try to extract episodes from seasons
            val episodes = mutableListOf<Episode>()
            
            if (seasonsTabs.isNotEmpty()) {
                // Has season tabs - extract from each season
                seasonsTabs.forEach { seasonTab ->
                    val seasonId = seasonTab.attr("data-serie")
                    val seasonNum = seasonId.toIntOrNull() ?: 1
                    
                    // Find the corresponding season episodes div
                    document.selectFirst(".SeasonsEpisodes[data-serie='$seasonId']")?.let { seasonDiv ->
                        seasonDiv.select("a[href*='watch.php']").forEach { episodeLink ->
                            val episodeUrl = episodeLink.attr("href").fixUrl()
                            val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                            val episodeTitle = episodeLink.select("span").text().takeIf { it.isNotBlank() } 
                                ?: "الحلقة $episodeNum"
                            
                            if (episodeUrl.isNotBlank()) {
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
                }
            } else if (hasMultipleEpisodes) {
                // No season tabs but has episodes - treat as single season
                document.select(".SeasonsEpisodes").forEach { episodeDiv ->
                    episodeDiv.select("a[href*='watch.php']").forEach { episodeLink ->
                        val episodeUrl = episodeLink.attr("href").fixUrl()
                        val episodeNum = episodeLink.select("em").text().toIntOrNull() ?: 0
                        val episodeTitle = episodeLink.select("span").text().takeIf { it.isNotBlank() } 
                            ?: "الحلقة $episodeNum"
                        
                        if (episodeUrl.isNotBlank()) {
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
            
            // If no episodes found but it's a series page (individual episode)
            if (episodes.isEmpty()) {
                val episodeNum = extractEpisodeNumberFromTitle(title)
                episodes.add(
                    newEpisode(url) {
                        this.name = title
                        this.episode = episodeNum
                        this.season = 1
                    }
                )
            }
            
            // Clean series title
            val seriesTitle = cleanSeriesTitle(title)
            
            newTvSeriesLoadResponse(seriesTitle, url, TvType.Anime, episodes.distinctBy { "${it.season}_${it.episode}" }) {
                this.posterUrl = poster.fixUrl()
                this.plot = description
            }
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // METHOD 1: Extract from server buttons
        document.select("#xservers button[data-embed]").forEach { serverButton ->
            val embedUrl = serverButton.attr("data-embed").trim()
            if (embedUrl.isNotBlank()) {
                foundLinks = true
                loadExtractor(embedUrl.fixUrl(), data, subtitleCallback, callback)
            }
        }

        // METHOD 2: Current iframe
        if (!foundLinks) {
            document.selectFirst("#Playerholder iframe[src]")?.let { iframe ->
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isNotBlank() && iframeSrc != "about:blank") {
                    foundLinks = true
                    loadExtractor(iframeSrc.fixUrl(), data, subtitleCallback, callback)
                }
            }
        }

        // METHOD 3: Download links
        if (!foundLinks) {
            document.select("a.dl.show_dl.api[href]").forEach { downloadLink ->
                val downloadUrl = downloadLink.attr("href").trim()
                if (downloadUrl.isNotBlank()) {
                    foundLinks = true
                    loadExtractor(downloadUrl.fixUrl(), data, subtitleCallback, callback)
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
        
        val title = cleanTitleText(rawTitle)
        if (title.isBlank()) return null
            
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        val poster = this.selectFirst("img.lazy")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("src")
            ?: ""
        
        // Check if it's a movie
        val isMovie = title.contains("فيلم") || 
                     title.contains("فلم") ||
                     this.select(".ribbon").any { it.text().contains("فيلم") || it.text().contains("فلم") } ||
                     href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, href.fixUrl(), TvType.Movie) {
                this.posterUrl = poster.fixUrl()
            }
        } else {
            newTvSeriesSearchResponse(title, href.fixUrl(), TvType.Anime) {
                this.posterUrl = poster.fixUrl()
            }
        }
    }

    private fun String.fixUrl(): String {
        return when {
            this.isBlank() -> ""
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            this.startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    private fun cleanTitleText(text: String): String {
        return text
            .replace(Regex("مرحباً في موقع.*"), "")
            .replace(Regex("انمي زد( الاصلي| الأصل)?"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractEpisodeNumberFromTitle(title: String): Int {
        val regex = Regex("الحلقة\\s*(\\d+)")
        return regex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    private fun cleanSeriesTitle(title: String): String {
        return title
            .replace(Regex("الحلقة\\s*\\d+.*"), "")
            .replace(Regex("الموسم\\s*\\d+.*"), "")
            .replace(Regex("الجزء\\s*\\d+.*"), "")
            .replace("مدبلجة", "")
            .replace("مترجمة", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { title }
    }
}
