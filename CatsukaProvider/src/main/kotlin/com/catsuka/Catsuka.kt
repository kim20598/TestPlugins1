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
                                
                                // Use VimeoExtractor - it returns Unit, so we just call it
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
                
                // Look for direct video element
                val video = document.selectFirst("video source[src]")
                if (video != null) {
                    val videoSrc = video.attr("src").takeIf { it.isNotBlank() }
                        ?.let { if (it.startsWith("http")) it else "https:$it" }
                    
                    if (videoSrc != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Direct Video",
                                url = videoSrc
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
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
                        
                        // Use VimeoExtractor - it returns Unit, so we just call it
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
            }
            
            false
        } catch (e: Exception) {
            false
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
