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

    // Add proper headers to avoid blocking
    override fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "max-age=0"
        )
    }
    
    private val playerUrl = "$mainUrl/player"
    
    // Simplified main page - only working categories
    override val mainPage = mainPageOf(
        "$playerUrl/?recherche=&sort=views" to "🔥 Most Viewed",
        "$playerUrl/?recherche=&sort=newest" to "🆕 Newest",
        "$playerUrl/categorie/episode" to "📺 Episodes",
        "$playerUrl/categorie/courtmetrage" to "🎬 Short Films",
        "$playerUrl/categorie/trailer" to "🎥 Trailers",
        "$playerUrl/categorie/clip" to "🎵 Music Videos",
        "$playerUrl/categorie/opening" to "🎭 Openings",
        "$playerUrl/categorie/pub" to "📢 Commercials"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        val pageNum = if (page > 1) page else 1
        
        return try {
            // Construct the correct URL with pagination
            val actualUrl = if (pageNum > 1) {
                if (url.contains("?")) "$url&page=$pageNum" else "$url?page=$pageNum"
            } else {
                url
            }
            
            val document = app.get(actualUrl, headers = getHeaders()).document
            
            // Debug: Print page structure
            println("=== CATSUKA DEBUG ===")
            println("URL: $actualUrl")
            println("Title: ${document.title()}")
            println("Body length: ${document.body().text().length}")
            
            // Try multiple selectors to find videos
            val items = extractVideosFromDocument(document)
            
            println("Found ${items.size} items")
            println("=== END DEBUG ===")
            
            newHomePageResponse(
                request.name,
                items,
                hasNext = items.isNotEmpty()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun extractVideosFromDocument(document: Element): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // METHOD 1: Look for video cards with proper structure
        document.select("article, .item, .video-card, .media-item, .swiper-slide").forEach { element ->
            val result = extractVideoFromElement(element)
            result?.let { items.add(it) }
        }
        
        // METHOD 2: Look for direct video links if METHOD 1 fails
        if (items.isEmpty()) {
            document.select("a[href*='/player/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("categories") && !href.contains("?")) {
                    val title = link.attr("title")
                        ?: link.selectFirst("img")?.attr("alt")
                        ?: link.text().trim()
                    
                    if (title.isNotBlank() && !title.contains("category", ignoreCase = true)) {
                        items.add(
                            newMovieSearchResponse(title, fixUrl(href), TvType.AnimeMovie) {
                                this.posterUrl = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                            }
                        )
                    }
                }
            }
        }
        
        // METHOD 3: Look for grid items
        if (items.isEmpty()) {
            document.select(".grid-item, .col, .thumbnail").forEach { element ->
                val link = element.selectFirst("a[href*='/player/']")
                link?.let {
                    val href = it.attr("href")
                    val title = element.selectFirst(".title, h3, .name")?.text()?.trim()
                        ?: it.attr("title")
                        ?: it.selectFirst("img")?.attr("alt")
                        ?: it.text().trim()
                    
                    if (title.isNotBlank()) {
                        items.add(
                            newMovieSearchResponse(title, fixUrl(href), TvType.AnimeMovie) {
                                this.posterUrl = it.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                            }
                        )
                    }
                }
            }
        }
        
        // Remove duplicates and limit
        return items.distinctBy { it.url }.take(50)
    }

    private fun extractVideoFromElement(element: Element): SearchResponse? {
        // Find the video link
        val link = element.selectFirst("a[href*='/player/']") ?: return null
        val href = link.attr("href")
        
        // Skip non-video links
        if (href.isBlank() || 
            href.contains("categories") || 
            href.contains("?page=") || 
            href.contains("?recherche=")) {
            return null
        }
        
        // Extract title
        val title = element.selectFirst(".title, h3, .name, .video-title, .caption")?.text()?.trim()
            ?: link.attr("title")
            ?: link.selectFirst("img")?.attr("alt")
            ?: element.selectFirst("img")?.attr("alt")
            ?: link.text().trim()
        
        if (title.isBlank() || title == "Lire" || title == "Voir") {
            return null
        }
        
        // Extract thumbnail
        val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?: element.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }
            ?: link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?: link.selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }
        
        // Extract duration if available
        val durationText = element.selectFirst(".duration, .time, .video-duration")?.text()?.trim()
        val duration = parseDuration(durationText)
        
        // Extract year from title if present
        val year = Regex("(\\d{4})").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // Determine type
        val tvType = when {
            title.contains("Movie", ignoreCase = true) || href.contains("/movie/") -> TvType.AnimeMovie
            title.contains("Episode", ignoreCase = true) || href.contains("/episode/") -> TvType.Anime
            else -> TvType.OVA
        }
        
        return newMovieSearchResponse(title, fixUrl(href), tvType) {
            this.posterUrl = poster
            if (duration > 0) this.duration = duration
            this.year = year
        }
    }

    private fun parseDuration(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        
        return try {
            when {
                text.contains(":")) -> {
                    val parts = text.split(":")
                    when (parts.size) {
                        2 -> parts[0].toInt() * 60 + parts[1].toInt()  // MM:SS
                        3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()  // HH:MM:SS
                        else -> 0
                    }
                }
                text.contains("min")) -> {
                    Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toInt()?.times(60) ?: 0
                }
                else -> text.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$playerUrl/?recherche=$encodedQuery"
        
        try {
            val document = app.get(searchUrl, headers = getHeaders()).document
            return extractVideosFromDocument(document)
                .filter { it.name.contains(query, ignoreCase = true) }
                .take(20)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val document = app.get(url, headers = getHeaders()).document
            
            // Extract metadata
            val title = document.selectFirst("meta[property='og:title']")?.attr("content")
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst(".video-title")?.text()?.trim()
                ?: "Catsuka Video"
            
            val description = document.selectFirst("meta[property='og:description']")?.attr("content")
                ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst(".description, .video-description")?.text()?.trim()
                ?: "Animation video from Catsuka"
            
            val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
                ?: document.selectFirst(".video-poster img")?.attr("src")?.let { fixUrl(it) }
                ?: document.selectFirst("img[src*='thumbnail']")?.attr("src")?.let { fixUrl(it) }
            
            val year = Regex("(\\d{4})").find(title)?.groupValues?.get(1)?.toIntOrNull()
            
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            val document = app.get(data, headers = getHeaders()).document
            
            // METHOD 1: Direct video source
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
            
            // METHOD 2: Embedded iframe
            val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='video']")
            val iframeSrc = iframe?.attr("src")
            
            if (iframeSrc != null && iframeSrc.isNotBlank()) {
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
                return true
            }
            
            // METHOD 3: Data attributes
            val dataVideo = document.selectFirst("[data-video-src], [data-src*='.mp4']")
            val dataVideoUrl = dataVideo?.attr("data-video-src") ?: dataVideo?.attr("data-src")
            
            if (dataVideoUrl != null && dataVideoUrl.isNotBlank()) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Data Video",
                        url = fixUrl(dataVideoUrl),
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = if (dataVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                               else ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
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