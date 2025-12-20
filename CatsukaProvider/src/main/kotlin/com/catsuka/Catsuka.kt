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

    // Catsuka Player main pages
    override val mainPage = mainPageOf(
        "$mainUrl/player/" to "Featured Videos",
        "$mainUrl/player/updates/" to "Latest Updates",
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/binge/" to "BINGE Series",
        "$mainUrl/player/categorie/courtmetrage" to "Short Films",
        "$mainUrl/player/categorie/clip" to "Music Videos",
        "$mainUrl/player/categorie/trailer" to "Trailers"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val document = app.get(request.data).document
            
            // CATSUKA SPECIFIC SELECTORS - Use the correct structure
            val items = document.select(".swiper-slide").mapNotNull { element ->
                parseSwiperSlide(element)
            }
            
            // Also check for featured videos in main slider
            val featuredItems = document.select(".item.video").mapNotNull { element ->
                parseFeaturedVideo(element)
            }
            
            val allItems = (items + featuredItems).distinctBy { it.url }
            
            return newHomePageResponse(request.name, allItems, hasNext = false)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    // Parse .swiper-slide elements (MOST COMMON in Catsuka)
    private fun parseSwiperSlide(element: Element): SearchResponse? {
        // Get link
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedUrl = fixUrl(href)
        
        // Get thumbnail image
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else fixUrl(it)
        }
        
        // Get title (different positions in different sections)
        val title = when {
            // For BINGE section (has <p> tag)
            element.selectFirst("p") != null -> {
                element.selectFirst("p")?.text()?.trim()
            }
            // For regular sections (has <span> tag)
            element.selectFirst("span") != null -> {
                element.selectFirst("span")?.text()?.trim()
            }
            // For image alt text
            else -> {
                img?.attr("alt")?.trim()
            }
        } ?: return null
        
        if (title.isBlank()) return null
        
        // Check if it's likely a series (BINGE section)
        val isSeries = href.contains("/videos/") || href.contains("binge") || title.contains("Seasons")
        
        return if (isSeries) {
            newAnimeSearchResponse(title, fixedUrl) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fixedUrl) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Parse featured videos in main slider (.item.video)
    private fun parseFeaturedVideo(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fixedUrl = fixUrl(href)
        
        // Get title from caption
        val title = element.selectFirst(".caption span:first-child")?.text()?.trim() ?: return null
        
        // Get poster from video element
        val posterUrl = element.selectFirst("video")?.attr("poster")?.let {
            if (it.startsWith("http")) it else fixUrl(it)
        }
        
        return newMovieSearchResponse(title, fixedUrl) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            // Catsuka uses POST search with "recherche" parameter
            val document = app.post(
                "$mainUrl/player/?recherche",
                data = mapOf("recherche" to query)
            ).document
            
            val items = document.select(".swiper-slide").mapNotNull { element ->
                parseSwiperSlide(element)
            }
            
            items.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            // Extract title
            val title = document.selectFirst("h1, .title, .caption span:first-child")?.text()?.trim()
                ?: "Unknown Title"
            
            // Extract poster
            val poster = document.selectFirst("video[poster], img[src*='vignettes'], img[src*='head']")?.attr("src")?.let {
                if (it.startsWith("http")) it else fixUrl(it)
            } ?: document.selectFirst("video")?.attr("poster")?.let {
                if (it.startsWith("http")) it else fixUrl(it)
            }
            
            // Extract description
            val plot = document.selectFirst(".description, .plot, p")?.text()?.trim()
                ?: document.selectFirst(".caption span:nth-child(2)")?.text()?.trim()
            
            // Check if it's a series
            val isSeries = url.contains("/videos/") || url.contains("binge") || url.contains("seasons")
            
            if (isSeries) {
                // Try to find episodes
                val episodes = mutableListOf<Episode>()
                
                document.select("a[href*='/videos/']").forEach { episodeLink ->
                    val epHref = episodeLink.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                    val epUrl = fixUrl(epHref)
                    val epTitle = episodeLink.text().trim().takeIf { it.isNotBlank() }
                        ?: episodeLink.selectFirst("img")?.attr("alt")
                        ?: "Episode"
                    
                    // Extract episode number
                    val epNum = Regex("""Episode\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""/(\d+)/?$""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 1
                    
                    episodes.add(
                        newEpisode(epUrl) {
                            name = epTitle
                            this.episode = epNum
                        }
                    )
                }
                
                // If no episodes found, check for series structure
                if (episodes.isEmpty()) {
                    // Look for series structure like "/videos/[series]/1"
                    val seriesMatch = Regex("""/videos/([^/]+)/\d+""").find(url)
                    if (seriesMatch != null) {
                        val seriesName = seriesMatch.groupValues[1]
                        // Create dummy episodes
                        for (i in 1..10) {
                            episodes.add(
                                newEpisode("$mainUrl/player/videos/$seriesName/$i") {
                                    name = "Episode $i"
                                    this.episode = i
                                }
                            )
                        }
                    } else {
                        // Create generic episodes
                        for (i in 1..5) {
                            episodes.add(
                                newEpisode("$url/$i") {
                                    name = "Episode $i"
                                    this.episode = i
                                }
                            )
                        }
                    }
                }
                
                return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // Single video/movie
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
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
                
                // Look for iframe
                val iframe = document.selectFirst("iframe[src]")
                val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                
                if (iframeSrc != null && loadExtractor(iframeSrc, subtitleCallback, callback)) {
                    return true
                }
                
                // Look for direct video
                val video = document.selectFirst("video source[src]")
                if (video != null) {
                    val videoSrc = video.attr("src").takeIf { it.isNotBlank() }
                        ?.let { if (it.startsWith("http")) it else "https:$it" }
                    
                    if (videoSrc != null) {
                        callback.invoke(
                            newExtractorLink(videoSrc) {
                                this.name = this@Catsuka.name
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }
                }
                
                // Look for embedded videos in scripts
                val scripts = document.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // YouTube
                    val youtubePattern = Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""")
                    val youtubeMatch = youtubePattern.find(scriptText)
                    if (youtubeMatch != null) {
                        val videoId = youtubeMatch.groupValues[1]
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        if (loadExtractor(youtubeUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                    
                    // Vimeo
                    val vimeoPattern = Regex("""vimeo\.com/(\d+)""")
                    val vimeoMatch = vimeoPattern.find(scriptText)
                    if (vimeoMatch != null) {
                        val videoId = vimeoMatch.groupValues[1]
                        val vimeoUrl = "https://player.vimeo.com/video/$videoId"
                        if (loadExtractor(vimeoUrl, subtitleCallback, callback)) {
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
