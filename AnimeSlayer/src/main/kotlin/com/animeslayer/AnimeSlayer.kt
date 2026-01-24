package com.animeslayer.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSlayer : MainAPI() {
    override var mainUrl = "https://animeslayerweb.com"
    override var name = "أنمي سلاير"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime, 
        TvType.AnimeMovie, 
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/animeslayer/" to "الصفحة الرئيسية",
        "$mainUrl/anime/?status=ongoing" to "الأنمي المستمر",
        "$mainUrl/anime/?status=completed&order=rating" to "الأنمي المكتمل",
        "$mainUrl/page/2/" to "المزيد من الأنمي"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            // Select the anime card
            val link = this.selectFirst("a") ?: return null
            val href = fixUrl(link.attr("href"))
            
            // Get title from multiple possible locations
            val title = link.selectFirst("h2")?.text()?.trim()
                ?: link.selectFirst(".tt")?.text()?.trim()
                ?: link.selectFirst(".limit")?.attr("title")
                ?: link.attr("title")
                ?: return null
            
            // Get poster URL
            val img = this.selectFirst("img")
            val posterUrl = img?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { src ->
                    when {
                        src.startsWith("http") -> src
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        else -> src
                    }
                }
            
            // Get episode number if available
            val episodeElement = this.selectFirst(".ep-number")
            val episodeText = episodeElement?.text()?.trim()
            val episodeNum = episodeText?.let { 
                Regex("""الحلقة\s+(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }
            
            return newAnimeSearchResponse(title, href) {
                this.posterUrl = posterUrl
                if (episodeNum != null) {
                    this.episodes = episodeNum
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val url = if (page > 1) {
                request.data + (if (request.data.contains("?")) "&" else "?") + "page=$page"
            } else {
                request.data
            }
            
            val document = app.get(url).document
            
            // Try multiple selectors for anime cards
            val items = mutableListOf<SearchResponse>()
            
            // Selector 1: Main page anime cards
            document.select("article.bs, .excstf .bsx, .listupd .bsx").forEach { element ->
                element.toSearchResult()?.let { items.add(it) }
            }
            
            // Selector 2: Alternative structure
            if (items.isEmpty()) {
                document.select(".bsx").forEach { element ->
                    element.toSearchResult()?.let { items.add(it) }
                }
            }
            
            val hasNext = items.isNotEmpty() && document.select(".hpage a").any { 
                it.text().contains("التالي", ignoreCase = true) 
            }
            
            newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = hasNext)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/?s=$encodedQuery"
            val document = app.get(searchUrl).document
            
            val items = mutableListOf<SearchResponse>()
            
            // Search results
            document.select("article.bs, .bsx, a[href*='/anime/']").forEach { element ->
                val href = element.selectFirst("a")?.attr("href")
                if (href != null && href.contains("/anime/")) {
                    element.toSearchResult()?.let { items.add(it) }
                }
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
            val title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst(".entry-title")?.text()?.trim()
                ?: "غير معروف"
            
            // Extract poster
            val poster = document.selectFirst("img[src*='.jpg'], img[src*='.jpeg'], img[src*='.png'], img[src*='.webp']")?.attr("src")?.let { src ->
                when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$mainUrl$src"
                    else -> src
                }
            }
            
            // Extract description
            val plot = document.selectFirst(".post__story, .description, .plot, .entry-content")?.text()?.trim()
            
            // Check if it's a movie or series
            val typeElement = document.selectFirst(".typez")
            val typeText = typeElement?.text()?.trim()?.lowercase()
            val isMovie = typeText?.contains("فيلم") == true || 
                         typeText?.contains("movie") == true ||
                         url.contains("/movie/", ignoreCase = true)
            
            // Extract episodes for series
            val episodes = mutableListOf<Episode>()
            
            if (!isMovie) {
                // Try to extract episodes from the page
                // Look for episode list
                document.select("a[href*='/episode'], a[href*='/ep-'], a[href*='/watch/']").forEach { epLink ->
                    val epHref = epLink.attr("href").takeIf { it.isNotBlank() }
                    if (epHref != null && (epHref.contains("/episode") || epHref.contains("/ep-") == true || epHref.contains("/watch/"))) {
                        val epText = epLink.text().trim()
                        val epNum = extractEpisodeNumber(epText)
                        
                        episodes.add(
                            newEpisode(fixUrl(epHref)) {
                                name = if (epText.isNotBlank()) epText else "الحلقة ${epNum ?: 1}"
                                episode = epNum
                                posterUrl = poster
                            }
                        )
                    }
                }
                
                // If no episodes found, try to extract from other patterns
                if (episodes.isEmpty()) {
                    // Try to extract anime ID for AJAX loading
                    val animeId = extractAnimeId(document, url)
                    
                    if (animeId != null) {
                        // Get episode count from metadata
                        val episodeCount = extractEpisodeCount(document)
                        
                        if (episodeCount > 0) {
                            for (i in 1..episodeCount) {
                                episodes.add(
                                    newEpisode("$animeId|$i") {
                                        name = "الحلقة $i"
                                        episode = i
                                        posterUrl = poster
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            // Create appropriate response based on type
            if (isMovie || episodes.isEmpty()) {
                newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = extractYear(document)
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = extractYear(document)
                }
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("خطأ في التحميل", url, TvType.AnimeMovie, url) {
                this.plot = "فشل في تحميل المحتوى: ${e.message}"
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
            // Check if data is a URL or contains episode info
            if (data.contains("|")) {
                // Data contains anime ID and episode number
                val parts = data.split("|")
                if (parts.size >= 2) {
                    val animeId = parts[0]
                    val episodeNum = parts[1].toIntOrNull() ?: 1
                    
                    // Try to get episode page
                    val episodeUrl = "$mainUrl/watch/$animeId-episode-$episodeNum"
                    extractVideoLinks(episodeUrl, subtitleCallback, callback)
                } else {
                    false
                }
            } else if (data.startsWith("http")) {
                // Data is a direct URL
                extractVideoLinks(data, subtitleCallback, callback)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractVideoLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(url).document
            
            // Method 1: Look for iframes
            var foundLinks = false
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() }
                if (src != null) {
                    val iframeUrl = when {
                        src.startsWith("http") -> src
                        src.startsWith("//") -> "https:$src"
                        else -> src
                    }
                    
                    if (loadExtractor(iframeUrl, url, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                }
            }
            
            // Method 2: Look for video players
            if (!foundLinks) {
                document.select("video source[src]").forEach { source ->
                    val videoUrl = source.attr("src").takeIf { it.isNotBlank() }
                    if (videoUrl != null) {
                        val fullUrl = when {
                            videoUrl.startsWith("http") -> videoUrl
                            videoUrl.startsWith("//") -> "https:$videoUrl"
                            videoUrl.startsWith("/") -> "$mainUrl$videoUrl"
                            else -> videoUrl
                        }
                        
                        val quality = extractQualityFromUrl(fullUrl)
                        val format = if (fullUrl.contains(".m3u8")) "M3U8" else "MP4"
                        
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$format - $quality",
                                url = fullUrl,
                                referer = mainUrl,
                                quality = getQualityValue(quality)
                            )
                        )
                        foundLinks = true
                    }
                }
            }
            
            // Method 3: Look for scripts with video data
            if (!foundLinks) {
                document.select("script").forEach { script ->
                    val scriptText = script.html()
                    
                    // Look for video URLs in scripts
                    val patterns = listOf(
                        Regex("""['"]src['"]\s*:\s*['"]([^'"]+)['"]"""),
                        Regex("""['"]file['"]\s*:\s*['"]([^'"]+)['"]"""),
                        Regex("""(https?://[^"']*\.(?:mp4|m3u8|mkv|webm)[^"']*)""")
                    )
                    
                    patterns.forEach { pattern ->
                        pattern.findAll(scriptText).forEach { match ->
                            val videoUrl = match.groupValues[1].takeIf { it.isNotBlank() }
                            if (videoUrl != null) {
                                val fullUrl = when {
                                    videoUrl.startsWith("http") -> videoUrl
                                    videoUrl.startsWith("//") -> "https:$videoUrl"
                                    else -> videoUrl
                                }
                                
                                if (fullUrl.contains(".m3u8")) {
                                    M3u8Helper.generateM3u8(
                                        source = name,
                                        streamUrl = fullUrl,
                                        referer = mainUrl,
                                        quality = getQualityValue(extractQualityFromUrl(fullUrl))
                                    ).forEach(callback)
                                } else {
                                    val quality = extractQualityFromUrl(fullUrl)
                                    callback.invoke(
                                        ExtractorLink(
                                            source = name,
                                            name = "فيديو مباشر - $quality",
                                            url = fullUrl,
                                            referer = mainUrl,
                                            quality = getQualityValue(quality)
                                        )
                                    )
                                }
                                foundLinks = true
                            }
                        }
                    }
                }
            }
            
            foundLinks
        } catch (e: Exception) {
            false
        }
    }
    
    // Helper functions
    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""الحلقة\s+(\d+)"""),
            Regex("""Episode\s+(\d+)"""),
            Regex("""Ep\.?\s*(\d+)"""),
            Regex("""\b(\d{1,3})\b""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }
    
    private fun extractAnimeId(document: org.jsoup.nodes.Document, url: String): String? {
        // Extract from URL
        val urlPath = url.removePrefix(mainUrl)
        val segments = urlPath.split("/")
        
        // Look for anime ID in URL
        for (segment in segments) {
            if (segment.isNotBlank() && segment != "anime" && segment != "watch" && 
                !segment.contains("?")) {
                return segment
            }
        }
        
        // Look for ID in page
        document.select("input[name='id'], [data-id], #anime_id").firstOrNull()?.let {
            return it.attr("value") ?: it.attr("data-id")
        }
        
        return null
    }
    
    private fun extractEpisodeCount(document: org.jsoup.nodes.Document): Int {
        // Look for episode count in page
        val episodeText = document.select(".ep-number, .episodes, .total-episodes").text()
        val match = Regex("""\d+""").find(episodeText)
        return match?.value?.toIntOrNull() ?: 0
    }
    
    private fun extractYear(document: org.jsoup.nodes.Document): Int? {
        // Look for year in page
        val yearText = document.select(".year, .release-date, .date").text()
        val match = Regex("""\b(19\d{2}|20\d{2})\b""").find(yearText)
        return match?.value?.toIntOrNull()
    }
    
    private fun extractQualityFromUrl(url: String): String {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains("4k") || urlLower.contains("2160p") -> "4K"
            urlLower.contains("1080p") || urlLower.contains("fullhd") -> "1080p"
            urlLower.contains("720p") || urlLower.contains("hd") -> "720p"
            urlLower.contains("480p") || urlLower.contains("sd") -> "480p"
            urlLower.contains("360p") -> "360p"
            else -> "مجهولة"
        }
    }
    
    private fun getQualityValue(quality: String): Int {
        return when (quality) {
            "4K" -> Qualities.P2160.value
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
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
