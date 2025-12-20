package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka Player"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/player/" to "All Videos",
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/updates/" to "Updates"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val url = request.data + if (page > 1) "?page=$page" else ""
            val document = app.get(url).document
            
            // Handle different page types
            val items = if (document.selectFirst(".zone") != null) {
                // This is a search result page or detail page structure
                document.select("div[style*='margin-bottom:20px']").mapNotNull { element ->
                    parseSearchResult(element)
                }
            } else {
                // This is the main page with swiper slides
                document.select(".swiper-slide, .item.video").mapNotNull { element ->
                    parseSwiperSlide(element)
                }
            }
            
            return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    // Parse swiper slides from main pages
    private fun parseSwiperSlide(element: Element): SearchResponse? {
        // Get link
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        // Get title
        val title = when {
            element.selectFirst("span") != null -> element.selectFirst("span")?.text()?.trim()
            element.selectFirst("p") != null -> element.selectFirst("p")?.text()?.trim()
            element.selectFirst(".caption span:first-child") != null -> 
                element.selectFirst(".caption span:first-child")?.text()?.trim()
            else -> null
        } ?: return null
        
        // Get thumbnail
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        return newAnimeSearchResponse(title, fixedHref) {
            this.posterUrl = posterUrl
        }
    }

    // Parse search results (different structure!)
    private fun parseSearchResult(element: Element): SearchResponse? {
        // In search results: <div class="gauche"><a><img></a></div>
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedHref = fixUrl(href)
        
        // Get title from: <span class="txtblanc14"><a><b>TITLE</b></a></span>
        val titleElement = element.selectFirst(".txtblanc14 a b, .lienblancrouge14 b")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Get thumbnail from: <div class="gauche"><a><img src="..."></a></div>
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        return newAnimeSearchResponse(title, fixedHref) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.post(
                "$mainUrl/player/?recherche",
                data = mapOf("recherche" to query)
            ).document
            
            // Search results use the .zone structure
            document.select("div[style*='margin-bottom:20px']").mapNotNull { element ->
                parseSearchResult(element)
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            // Try different selectors for title
            val title = document.selectFirst("h1, .title, .txtblanc14 b, .zonetitre .divorangegrand")?.text()?.trim() 
                ?: "Unknown Title"
            
            // Try different selectors for poster
            val poster = document.selectFirst("img[src*='vignettes'], img[src*='head'], video[poster]")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
            } ?: document.selectFirst("video")?.attr("poster")?.let {
                if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
            }
            
            val plot = document.selectFirst(".description, .plot, p")?.text()?.trim()
            
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.plot = "Failed to load: ${e.message}"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (data.startsWith("http")) {
                val document = app.get(data).document
                
                // Look for iframe first (Vimeo, YouTube embeds)
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val iframeSrc = iframe.attr("src").takeIf { it.isNotBlank() }
                        ?.let { if (it.startsWith("http")) it else "https:$it" }
                    
                    if (iframeSrc != null) {
                        // Check if it's Vimeo
                        if (iframeSrc.contains("vimeo.com")) {
                            // Extract Vimeo video ID
                            val vimeoPattern = Regex("""vimeo\.com/(?:video/)?(\d+)""")
                            val vimeoMatch = vimeoPattern.find(iframeSrc)
                            if (vimeoMatch != null) {
                                val videoId = vimeoMatch.groupValues[1]
                                val vimeoUrl = "https://vimeo.com/$videoId"
                                
                                // Use VimeoExtractor
                                val extractor = VimeoExtractor()
                                extractor.getUrl(vimeoUrl, data, subtitleCallback, callback)
                                return true
                            }
                        }
                        
                        // Check if it's YouTube
                        if (iframeSrc.contains("youtube.com") || iframeSrc.contains("youtu.be")) {
                            // Extract YouTube video ID
                            val youtubePattern = Regex("""(?:youtube\.com/embed/|youtu\.be/|youtube\.com/watch\?v=)([A-Za-z0-9_-]{11})""")
                            val youtubeMatch = youtubePattern.find(iframeSrc)
                            if (youtubeMatch != null) {
                                val videoId = youtubeMatch.groupValues[1]
                                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                                
                                // CloudStream's YouTube extractor handles qualities
                                if (loadExtractor(youtubeUrl, subtitleCallback, callback)) {
                                    return true
                                }
                            }
                        }
                        
                        // Try loading the iframe source with extractor
                        if (loadExtractor(iframeSrc, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
                
                // Look for direct video element with source tag - FIXED VERSION
                val videoSources = document.select("video source[src]")
                for (video in videoSources) {
                    val videoSrc = video.attr("src").takeIf { it.isNotBlank() }
                        ?.let { src ->
                            when {
                                src.startsWith("http") -> src
                                src.startsWith("//") -> "https:$src"
                                src.startsWith("/") -> "$mainUrl$src"
                                else -> "$mainUrl/$src"
                            }
                        }
                    
                    if (videoSrc != null) {
                        // Determine quality from URL and type
                        val quality = determineQualityFromUrl(videoSrc)
                        val type = video.attr("type").takeIf { it.isNotBlank() }
                        val format = when {
                            type?.contains("mp4") == true -> "MP4"
                            type?.contains("webm") == true -> "WebM"
                            videoSrc.contains(".mp4") -> "MP4"
                            videoSrc.contains(".webm") -> "WebM"
                            else -> "Video"
                        }
                        
                        val qualityName = when (quality) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1440.value -> "1440p"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            Qualities.P360.value -> "360p"
                            Qualities.P240.value -> "240p"
                            else -> "Unknown"
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$format - $qualityName",
                                url = videoSrc
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                            }
                        )
                        return true
                    }
                }
                
                // Look for video tag with direct src attribute
                val videoTags = document.select("video[src]")
                for (videoTag in videoTags) {
                    val videoSrc = videoTag.attr("src").takeIf { it.isNotBlank() }
                        ?.let { src ->
                            when {
                                src.startsWith("http") -> src
                                src.startsWith("//") -> "https:$src"
                                src.startsWith("/") -> "$mainUrl$src"
                                else -> "$mainUrl/$src"
                            }
                        }
                    
                    if (videoSrc != null) {
                        val quality = determineQualityFromUrl(videoSrc)
                        val qualityName = when (quality) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1440.value -> "1440p"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            Qualities.P360.value -> "360p"
                            Qualities.P240.value -> "240p"
                            else -> "Unknown"
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Direct Video - $qualityName",
                                url = videoSrc
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                            }
                        )
                        return true
                    }
                }
                
                // Look for Vimeo video ID in scripts (fallback)
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // Vimeo pattern
                    val vimeoPattern = Regex("""vimeo\.com/(?:video/)?(\d+)""")
                    val vimeoMatch = vimeoPattern.find(scriptText)
                    if (vimeoMatch != null) {
                        val videoId = vimeoMatch.groupValues[1]
                        val vimeoUrl = "https://vimeo.com/$videoId"
                        
                        val extractor = VimeoExtractor()
                        extractor.getUrl(vimeoUrl, data, subtitleCallback, callback)
                        return true
                    }
                    
                    // YouTube pattern
                    val youtubePattern = Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""")
                    val youtubeMatch = youtubePattern.find(scriptText)
                    if (youtubeMatch != null) {
                        val videoId = youtubeMatch.groupValues[1]
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        
                        if (loadExtractor(youtubeUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
                
                // NEW: Check for video links in page (a tags with video extensions)
                val videoLinks = document.select("a[href*='.mp4'], a[href*='.webm'], a[href*='.m3u8']")
                for (link in videoLinks) {
                    val href = link.attr("href").takeIf { it.isNotBlank() }
                    if (href != null && (href.contains(".mp4") || href.contains(".webm") || href.contains(".m3u8"))) {
                        val videoSrc = when {
                            href.startsWith("http") -> href
                            href.startsWith("//") -> "https:$href"
                            href.startsWith("/") -> "$mainUrl$href"
                            else -> "$mainUrl/$href"
                        }
                        
                        val quality = determineQualityFromUrl(videoSrc)
                        val qualityName = when (quality) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1440.value -> "1440p"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            Qualities.P360.value -> "360p"
                            Qualities.P240.value -> "240p"
                            else -> "Unknown"
                        }
                        
                        if (videoSrc.contains(".m3u8")) {
                            // Handle HLS streams
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = videoSrc,
                                referer = mainUrl,
                                quality = quality
                            ).forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Direct Video - $qualityName",
                                    url = videoSrc
                                ) {
                                    this.referer = mainUrl
                                    this.quality = quality
                                }
                            )
                        }
                        return true
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // Helper function to determine quality from URL/filename
    private fun determineQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        
        return when {
            urlLower.contains("4k") || urlLower.contains("2160") -> Qualities.P2160.value
            urlLower.contains("1440") || urlLower.contains("2k") -> Qualities.P1440.value
            urlLower.contains("1080") || urlLower.contains("fullhd") -> Qualities.P1080.value
            urlLower.contains("720") || urlLower.contains("hd") -> Qualities.P720.value
            urlLower.contains("480") || urlLower.contains("sd") -> Qualities.P480.value
            urlLower.contains("360") -> Qualities.P360.value
            urlLower.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
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
}
