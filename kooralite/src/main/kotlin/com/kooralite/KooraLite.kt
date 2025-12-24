package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URI

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.fullmatch-hd.com"
    override var name = "KooraLite - كورة لايت"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    
    private val customPosterUrl = "https://raw.githubusercontent.com/kim20598/TestPlugins1/master/kooralite/images.png"

    private fun decodeBase64(encoded: String): String {
        return try {
            String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            encoded
        }
    }

    private fun Element.toMatchSearchResponse(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(link)

        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: ""
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: ""

        if (team1.isBlank() || team2.isBlank()) return null

        val title = "$team1 vs $team2"
        val time = selectFirst(".MT_Time")?.text()?.trim() ?: ""
        val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim() ?: ""
        
        val poster = customPosterUrl

        val statusClass = classNames().firstOrNull { it in listOf("live", "finished", "coming-soon") } ?: ""
        val statusText = when (statusClass) {
            "live" -> "🔴 مباشر"
            "finished" -> "✅ انتهت"
            "coming-soon" -> "⏳ قادم"
            else -> {
                val txt = selectFirst(".MT_Status")?.text()?.trim()
                when {
                    txt?.contains("مباشر") == true -> "🔴 مباشر"
                    txt?.contains("انتهت") == true -> "✅ انتهت"
                    txt != null && txt.isNotBlank() -> "⏳ $txt"
                    else -> "⏳ قادم"
                }
            }
        }

        val enhancedTitle = if (time.isNotBlank()) {
            "$statusText $title ($time)"
        } else {
            "$statusText $title"
        }

        val matchData = listOf(title, time, tournament, statusClass, poster, team1, team2, "", "")
            .joinToString("|")
        val dataUrl = "$href|$matchData"

        return newMovieSearchResponse(enhancedTitle, dataUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "مباريات اليوم",
        "$mainUrl/matches-today/" to "المباريات الحية",
        "$mainUrl/matches-yesterday/" to "مباريات الأمس",
        "$mainUrl/matches-tomorrow/" to "مباريات الغد"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document

        val items = mutableListOf<SearchResponse>()

        document.select(".AY_Match").forEach { match ->
            match.toMatchSearchResponse()?.let { items.add(it) }
        }

        return newHomePageResponse(request.name, items, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"

        return try {
            val document = app.get(searchUrl).document
            val results = mutableListOf<SearchResponse>()

            document.select(".AY_Match").forEach { match ->
                match.toMatchSearchResponse()?.let { results.add(it) }
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|")
        val actualUrl = parts[0]
        val title = parts.getOrNull(1) ?: "مباراة كرة قدم"
        val time = parts.getOrNull(2) ?: ""
        val tournament = parts.getOrNull(3) ?: ""
        val status = parts.getOrNull(4) ?: ""
        val poster = customPosterUrl
        val team1 = parts.getOrNull(6) ?: ""
        val team2 = parts.getOrNull(7) ?: ""

        val description = buildString {
            if (team1.isNotBlank() && team2.isNotBlank()) {
                append("⚽ $team1 vs $team2\n")
            }
            if (time.isNotBlank()) {
                append("🕒 الوقت: $time\n")
            }
            if (tournament.isNotBlank()) {
                append("🏆 البطولة: $tournament\n")
            }
            when (status) {
                "live" -> append("🔴 الحالة: البث مباشر الآن\n")
                "finished" -> append("✅ الحالة: انتهت المباراة\n")
                else -> append("⏳ الحالة: قادمة\n")
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, actualUrl) {
            this.posterUrl = poster
            this.plot = description.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val dataUrl = fixUrl(data)
            
            if (dataUrl.contains("albaplayer")) {
                return extractAllContent(dataUrl, callback)
            }
            
            val doc = app.get(dataUrl).document
            var foundLinks = false
            
            doc.select("iframe[src*='albaplayer']").forEach { iframe ->
                val iframeSrc = fixUrl(iframe.attr("src"))
                foundLinks = extractAllContent(iframeSrc, callback) || foundLinks
            }
            
            doc.select("iframe").forEach { iframe ->
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    try {
                        val fullUrl = fixUrl(iframeSrc)
                        if (fullUrl.contains("albaplayer")) {
                            foundLinks = extractAllContent(fullUrl, callback) || foundLinks
                        }
                    } catch (e: Exception) {
                        // Continue
                    }
                }
            }
            
            return foundLinks
            
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun extractAllContent(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAnyLink = false
        
        try {
            val mainDoc = app.get(url).document
            
            val baseDomain = try {
                val uri = URI(url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                val domainRegex = Regex("""(https?://[^/]+)""")
                domainRegex.find(url)?.groupValues?.get(1) ?: "https://lp.kooralive.cfd"
            }
            
            // Collect ALL available servers
            val serverLinks = mainDoc.select(".aplr-menu a.aplr-link")
            val servers = mutableListOf<Pair<String, String>>()
            
            serverLinks.forEach { link ->
                val serverName = link.text().trim()
                var serverHref = link.attr("href")
                
                if (serverName.isNotBlank() && serverHref.isNotBlank()) {
                    if (!serverHref.startsWith("http")) {
                        serverHref = if (serverHref.startsWith("/")) {
                            "$baseDomain$serverHref"
                        } else {
                            "$baseDomain/$serverHref"
                        }
                    }
                    servers.add(Pair(serverName, serverHref))
                }
            }
            
            // Process current page AND all servers
            val processedUrls = mutableSetOf<String>()
            val allSources = mutableListOf<Pair<String, String>>()
            
            // Add current page
            allSources.add(Pair("Current Page", url))
            // Add all servers
            allSources.addAll(servers)
            
            // Process each source to get stream URLs
            for ((serverName, sourceUrl) in allSources) {
                try {
                    val sourceDoc = if (sourceUrl == url) mainDoc else app.get(sourceUrl).document
                    
                    // Extract base64 encoded URLs
                    val scripts = sourceDoc.select("script").html()
                    val regexPatterns = listOf(
                        Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)"""),
                        Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','plyr'\)"""),
                        Regex("""AlbaPlayerControl\("([A-Za-z0-9+/=]+)","hls"\)"""),
                        Regex("""AlbaPlayerControl\("([A-Za-z0-9+/=]+)","plyr"\)""")
                    )
                    
                    var base64String: String? = null
                    for (pattern in regexPatterns) {
                        val match = pattern.find(scripts)
                        if (match != null) {
                            base64String = match.groupValues[1]
                            break
                        }
                    }
                    
                    if (base64String != null) {
                        val decodedUrl = decodeBase64(base64String)
                        if (decodedUrl.isNotBlank() && !processedUrls.contains(decodedUrl)) {
                            processedUrls.add(decodedUrl)
                            
                            val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                            
                            // Check if this is an m3u8 playlist with multiple qualities
                            if (streamUrl.contains(".m3u8")) {
                                // Try to extract quality variants from the m3u8
                                foundAnyLink = extractQualitiesFromM3u8(streamUrl, sourceUrl, serverName, callback) || foundAnyLink
                            } else {
                                // Fallback: create single link
                                val extractorLink = newExtractorLink(
                                    name,
                                    serverName,
                                    streamUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = sourceUrl
                                    this.quality = Qualities.P720.value
                                }
                                callback.invoke(extractorLink)
                                foundAnyLink = true
                            }
                        }
                    }
                    
                    // Also check for direct m3u8 links
                    val directM3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                    val allText = sourceDoc.html()
                    val m3u8Matches = directM3u8Regex.findAll(allText)
                    
                    for (match in m3u8Matches) {
                        val streamUrl = match.groupValues[1]
                        if (streamUrl.contains("m3u8") && !processedUrls.contains(streamUrl)) {
                            processedUrls.add(streamUrl)
                            
                            // Check if this is an m3u8 playlist with multiple qualities
                            if (streamUrl.contains(".m3u8")) {
                                foundAnyLink = extractQualitiesFromM3u8(streamUrl, sourceUrl, "$serverName (Direct)", callback) || foundAnyLink
                            } else {
                                val extractorLink = newExtractorLink(
                                    name,
                                    "$serverName (Direct)",
                                    streamUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = sourceUrl
                                    this.quality = Qualities.P720.value
                                }
                                callback.invoke(extractorLink)
                                foundAnyLink = true
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    // Continue to next source
                }
            }
            
            // Handle YouTube embeds separately
            val youtubeIframes = mainDoc.select("iframe[src*='youtube.com'], iframe[src*='youtu.be']")
            for (iframe in youtubeIframes) {
                val youtubeSrc = iframe.attr("src")
                if (youtubeSrc.isNotBlank()) {
                    val videoIdRegex = Regex("""(?:youtube\.com\/embed\/|youtu\.be\/|youtube\.com\/v\/|youtube\.com\/watch\?v=)([^&?/\s]+)""")
                    val match = videoIdRegex.find(youtubeSrc)
                    match?.let {
                        val videoId = it.groupValues[1]
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        
                        val extractorLink = newExtractorLink(
                            name,
                            "YouTube",
                            youtubeUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P720.value
                        }
                        
                        callback.invoke(extractorLink)
                        foundAnyLink = true
                    }
                }
            }
            
        } catch (e: Exception) {
            // Do nothing
        }
        
        return foundAnyLink
    }
    
    /**
     * Extract quality variants from an m3u8 playlist
     */
    private suspend fun extractQualitiesFromM3u8(
        m3u8Url: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            // Download the m3u8 playlist
            val response = app.get(m3u8Url, referer = referer)
            val content = response.text
            
            if (content.isBlank()) return false
            
            // Parse the m3u8 to find quality variants
            val lines = content.lines()
            val qualityMap = mutableMapOf<String, Pair<String, Int>>()
            
            var currentBandwidth: String? = null
            var currentResolution: String? = null
            var currentUrl: String? = null
            
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> {
                        // Extract bandwidth and resolution
                        currentBandwidth = extractM3u8Attribute(line, "BANDWIDTH")
                        currentResolution = extractM3u8Attribute(line, "RESOLUTION")
                    }
                    !line.startsWith("#") && line.isNotBlank() -> {
                        currentUrl = line
                        
                        // If we have all info, add to map
                        if (currentBandwidth != null && currentUrl != null) {
                            val quality = calculateQualityFromM3u8(currentBandwidth, currentResolution)
                            qualityMap[currentUrl] = Pair("${getQualityLabel(quality)}", quality)
                            
                            // Reset for next variant
                            currentBandwidth = null
                            currentResolution = null
                            currentUrl = null
                        }
                    }
                }
            }
            
            // If no variants found (simple m3u8), create single link
            if (qualityMap.isEmpty()) {
                val extractorLink = newExtractorLink(
                    name,
                    serverName,
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.P720.value
                }
                callback.invoke(extractorLink)
                foundLinks = true
            } else {
                // Create separate links for each quality variant
                for ((variantUrl, qualityInfo) in qualityMap) {
                    val (qualityLabel, qualityValue) = qualityInfo
                    
                    // Make sure variant URL is absolute
                    val absoluteUrl = if (variantUrl.startsWith("http")) {
                        variantUrl
                    } else if (variantUrl.startsWith("/")) {
                        // Extract base URL from m3u8Url
                        val baseUrl = m3u8Url.substringBeforeLast("/")
                        "$baseUrl/$variantUrl"
                    } else {
                        // Relative URL
                        val baseUrl = m3u8Url.substringBeforeLast("/")
                        "$baseUrl/$variantUrl"
                    }
                    
                    val extractorLink = newExtractorLink(
                        name,
                        "$serverName [$qualityLabel]",
                        absoluteUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = qualityValue
                    }
                    callback.invoke(extractorLink)
                    foundLinks = true
                }
            }
            
        } catch (e: Exception) {
            // Fallback: create single link if parsing fails
            try {
                val extractorLink = newExtractorLink(
                    name,
                    serverName,
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.P720.value
                }
                callback.invoke(extractorLink)
                foundLinks = true
            } catch (e2: Exception) {
                // Ignore
            }
        }
        
        return foundLinks
    }
    
    /**
     * Extract attribute from m3u8 EXT-X-STREAM-INF line
     */
    private fun extractM3u8Attribute(line: String, attribute: String): String? {
        val regex = Regex("$attribute=(\\d+)")
        return regex.find(line)?.groupValues?.get(1)
    }
    
    /**
     * Calculate quality from bandwidth and resolution
     */
    private fun calculateQualityFromM3u8(bandwidth: String?, resolution: String?): Int {
        if (resolution != null) {
            // Parse resolution like "1920x1080"
            val parts = resolution.split("x")
            if (parts.size == 2) {
                val height = parts[1].toIntOrNull() ?: 0
                return when {
                    height >= 2160 -> Qualities.P2160.value
                    height >= 1080 -> Qualities.P1080.value
                    height >= 720 -> Qualities.P720.value
                    height >= 480 -> Qualities.P480.value
                    height >= 360 -> Qualities.P360.value
                    else -> Qualities.P240.value
                }
            }
        }
        
        // Fallback to bandwidth
        val bandwidthValue = bandwidth?.toIntOrNull() ?: 0
        return when {
            bandwidthValue >= 8000000 -> Qualities.P2160.value  // 8+ Mbps for 4K
            bandwidthValue >= 4000000 -> Qualities.P1080.value  // 4+ Mbps for 1080p
            bandwidthValue >= 2000000 -> Qualities.P720.value   // 2+ Mbps for 720p
            bandwidthValue >= 1000000 -> Qualities.P480.value   // 1+ Mbps for 480p
            else -> Qualities.P360.value
        }
    }
    
    /**
     * Convert quality value to readable label
     */
    private fun getQualityLabel(quality: Int): String {
        return when (quality) {
            Qualities.P2160.value -> "4K"
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value -> "720p"
            Qualities.P480.value -> "480p"
            Qualities.P360.value -> "360p"
            Qualities.P240.value -> "240p"
            else -> "Auto"
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
}
