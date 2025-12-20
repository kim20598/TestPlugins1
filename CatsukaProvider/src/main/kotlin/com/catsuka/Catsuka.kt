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

    // Main page categories
    override val mainPage = mainPageOf(
        "$mainUrl/player/" to "Featured Videos",
        "$mainUrl/player/updates/" to "Latest Updates",
        "$mainUrl/player/highlights/" to "Highlights",
        "$mainUrl/player/categories/" to "Categories",
        "$mainUrl/player/binge/" to "BINGE Series",
        "$mainUrl/player/categorie/courtmetrage" to "Short Films",
        "$mainUrl/player/categorie/clip" to "Music Videos",
        "$mainUrl/player/categorie/trailer" to "Trailers",
        "$mainUrl/player/categorie/demoreel" to "Demo Reels",
        "$mainUrl/player/categorie/episode" to "Episodes"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        
        // CATSUKA SPECIFIC SELECTORS:
        val items = mutableListOf<SearchResponse>()
        
        when (request.name) {
            "Featured Videos" -> {
                // Main page has videos in multiple sections
                document.select(".swiper-slide").forEach { element ->
                    parseVideoCard(element)?.let { items.add(it) }
                }
                // Also get from main slider
                document.select(".item.video").forEach { element ->
                    parseMainSlider(element)?.let { items.add(it) }
                }
            }
            "Latest Updates" -> {
                document.select(".swiper-slide").forEach { element ->
                    parseVideoCard(element)?.let { items.add(it) }
                }
            }
            "BINGE Series" -> {
                document.select(".swiper-slide").forEach { element ->
                    parseBingeCard(element)?.let { items.add(it) }
                }
            }
            else -> {
                // For categories and other pages
                document.select(".swiper-slide").forEach { element ->
                    parseVideoCard(element)?.let { items.add(it) }
                }
            }
        }
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = false)
    }

    // Parse regular video cards (for updates, highlights, categories)
    private fun parseVideoCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href")) ?: return null
        
        val img = element.selectFirst("img")
        val titleElement = element.selectFirst("span") ?: return null
        val title = titleElement.text().trim()
        
        if (title.isBlank()) return null
        
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        return newMovieSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    // Parse BINGE section cards (different structure)
    private fun parseBingeCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href")) ?: return null
        
        val img = element.selectFirst("img")
        val titleElement = element.selectFirst("p") ?: return null
        val title = titleElement.text().trim()
        
        if (title.isBlank()) return null
        
        val posterUrl = img?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        // BINGE items are usually series
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    // Parse main slider videos
    private fun parseMainSlider(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href")) ?: return null
        
        val titleElement = element.selectFirst(".caption span:first-child") ?: return null
        val title = titleElement.text().trim()
        
        if (title.isBlank()) return null
        
        // Get poster from video element
        val poster = element.selectFirst("video")?.attr("poster")?.let {
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        return newMovieSearchResponse(title, href) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            // Catsuka uses POST search with "recherche" parameter
            val document = app.post(
                "$mainUrl/player/?recherche",
                data = mapOf("recherche" to query)
            ).document
            
            val items = mutableListOf<SearchResponse>()
            
            // Look for search results in different sections
            document.select(".swiper-slide").forEach { element ->
                parseVideoCard(element)?.let { items.add(it) }
            }
            
            // Also check main content
            document.select(".item.video").forEach { element ->
                parseMainSlider(element)?.let { items.add(it) }
            }
            
            items.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title from page
        val title = document.selectFirst("h1, .title, .caption span:first-child")?.text()?.trim()
            ?: "Unknown Title"
        
        // Extract poster
        val poster = document.selectFirst("video[poster], img[src*='vignettes'], img[src*='head']")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        } ?: document.selectFirst("video")?.attr("poster")?.let {
            if (it.startsWith("http")) it else "$mainUrl/$it".removePrefix("$mainUrl//")
        }
        
        // Extract description
        val description = document.selectFirst(".description, .plot, p")?.text()?.trim()
            ?: document.selectFirst(".caption span:nth-child(2)")?.text()?.trim()
        
        // Check if it's a series (BINGE section or videos with episodes)
        val isSeries = url.contains("/videos/") || url.contains("binge") || url.contains("seasons")
        
        if (isSeries) {
            // For series, extract episodes
            val episodes = mutableListOf<Episode>()
            
            // Try to find episode list
            document.select("a[href*='/player/'], a[href*='/videos/']").forEach { episodeLink ->
                val epUrl = fixUrl(episodeLink.attr("href")) ?: return@forEach
                val epTitle = episodeLink.text().trim().takeIf { it.isNotBlank() }
                    ?: episodeLink.selectFirst("img")?.attr("alt")
                    ?: "Episode"
                
                // Extract episode number from title or URL
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
            
            // If no episodes found, create dummy episodes
            if (episodes.isEmpty()) {
                for (i in 1..10) {
                    episodes.add(
                        newEpisode("$url/$i") {
                            name = "Episode $i"
                            this.episode = i
                        }
                    )
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Single movie/video
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
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
            // Check if data is a Catsuka video page
            if (data.startsWith(mainUrl)) {
                val document = app.get(data).document
                
                // First, look for iframe
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val iframeSrc = fixUrl(iframe.attr("src"))
                    if (loadExtractor(iframeSrc, subtitleCallback, callback)) {
                        return true
                    }
                }
                
                // Look for video element
                val video = document.selectFirst("video source[src]")
                if (video != null) {
                    val videoSrc = fixUrl(video.attr("src"))
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            videoSrc,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value
                        )
                    )
                    return true
                }
                
                // Look for YouTube embeds
                val scripts = document.select("script")
                scripts.forEach { script ->
                    val scriptText = script.html()
                    
                    // YouTube pattern
                    val youtubePattern = Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""")
                    youtubePattern.find(scriptText)?.let { match ->
                        val videoId = match.groupValues[1]
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        if (loadExtractor(youtubeUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                    
                    // Vimeo pattern
                    val vimeoPattern = Regex("""vimeo\.com/(\d+)""")
                    vimeoPattern.find(scriptText)?.let { match ->
                        val videoId = match.groupValues[1]
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
    
    private fun fixUrl(url: String): String? {
        if (url.isBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
