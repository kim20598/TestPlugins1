package com.catsuka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.OVA
    )

    private val playerUrl = "$mainUrl/player"
    
    // Working categories based on actual Catsuka structure
    override val mainPage = mainPageOf(
        "$playerUrl/?recherche=&sort=views" to "🔥 Most Viewed",
        "$playerUrl/?recherche=&sort=newest" to "🆕 Newest",
        "$playerUrl/categorie/episode" to "📺 Episodes",
        "$playerUrl/categorie/courtmetrage" to "🎬 Short Films",
        "$playerUrl/categorie/trailer" to "🎥 Trailers"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        val pageNum = if (page > 1) page else 1
        
        return try {
            // Construct URL with pagination
            val actualUrl = if (pageNum > 1) {
                if (url.contains("?")) "$url&page=$pageNum" else "$url?page=$pageNum"
            } else {
                url
            }
            
            val document = app.get(actualUrl).document
            
            // Extract video items
            val items = extractVideosFromDocument(document)
            
            // FIXED: Use HomePageResponse constructor instead of newHomePageResponse
            HomePageResponse(
                listOf(HomePageList(request.name, items)),
                hasNext = items.isNotEmpty()
            )
        } catch (e: Exception) {
            // FIXED: Use HomePageResponse constructor instead of newHomePageResponse
            HomePageResponse(
                listOf(HomePageList(request.name, emptyList())),
                hasNext = false
            )
        }
    }

    private fun extractVideosFromDocument(document: Element): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // METHOD 1: Look for video items with images
        document.select("a[href*='/player/']").forEach { link ->
            val href = link.attr("href")
            
            // Skip non-video links
            if (href.isBlank() || 
                href.contains("categories") || 
                href.contains("?page=") || 
                href.contains("?recherche=")) {
                return@forEach
            }
            
            // Try to get title
            val title = link.attr("title")
                ?: link.selectFirst("img")?.attr("alt")
                ?: link.selectFirst(".title")?.text()?.trim()
                ?: link.text().trim()
            
            if (title.isBlank() || 
                title == "Lire" || 
                title == "Voir" || 
                title.contains("category", ignoreCase = true)) {
                return@forEach
            }
            
            // Get poster
            val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                ?: link.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }
            
            items.add(
                newMovieSearchResponse(title, fixUrl(href), TvType.AnimeMovie) {
                    this.posterUrl = poster
                }
            )
        }
        
        // METHOD 2: Look for video cards/articles if METHOD 1 doesn't find enough
        if (items.size < 5) {
            document.select("article, .item, .video-card").forEach { element ->
                val link = element.selectFirst("a[href*='/player/']") ?: return@forEach
                val href = link.attr("href")
                
                if (href.isBlank() || href.contains("categories")) return@forEach
                
                val title = element.selectFirst(".title, h3, .name")?.text()?.trim()
                    ?: link.attr("title")
                    ?: link.selectFirst("img")?.attr("alt")
                    ?: link.text().trim()
                
                if (title.isBlank()) return@forEach
                
                val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    ?: link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                
                items.add(
                    newMovieSearchResponse(title, fixUrl(href), TvType.AnimeMovie) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        
        // Remove duplicates and limit
        return items.distinctBy { it.url }.take(30)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$playerUrl/?recherche=$encodedQuery"
        
        try {
            val document = app.get(searchUrl).document
            return extractVideosFromDocument(document)
                .filter { it.name.contains(query, ignoreCase = true) }
                .take(20)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val document = app.get(url).document
            
            // Extract metadata
            val title = document.selectFirst("meta[property='og:title']")?.attr("content")
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: "Catsuka Video"
            
            val description = document.selectFirst("meta[property='og:description']")?.attr("content")
                ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?: "Animation video from Catsuka"
            
            val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
                ?: document.selectFirst("img[src*='thumbnail']")?.attr("src")?.let { fixUrl(it) }
            
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } catch (e: Exception) {
            return newMovieLoadResponse("Catsuka Video", url, TvType.AnimeMovie, url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            
            // Look for video source
            val videoSource = document.selectFirst("video source[src], video[src]")
            val videoUrl = videoSource?.attr("src")
            
            if (videoUrl != null && videoUrl.isNotBlank()) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Direct Video",
                        url = fixUrl(videoUrl),
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                               else ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
            
            // Look for iframe
            val iframe = document.selectFirst("iframe[src*='player']")
            val iframeSrc = iframe?.attr("src")
            
            if (iframeSrc != null && iframeSrc.isNotBlank()) {
                // Directly extract video from iframe instead of using loadExtractor
                try {
                    val iframeDoc = app.get(fixUrl(iframeSrc)).document
                    val iframeVideo = iframeDoc.selectFirst("video source[src], video[src]")
                    val iframeVideoUrl = iframeVideo?.attr("src")
                    
                    if (iframeVideoUrl != null && iframeVideoUrl.isNotBlank()) {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "Direct Video",
                                url = fixUrl(iframeVideoUrl),
                                referer = fixUrl(iframeSrc),
                                quality = Qualities.Unknown.value,
                                type = if (iframeVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                                       else ExtractorLinkType.VIDEO
                            )
                        )
                        return true
                    }
                } catch (e: Exception) {
                    // If we can't extract from iframe, just return false
                }
                return false
            }
            
        } catch (e: Exception) {
            // Ignore errors
        }
        
        return false
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
