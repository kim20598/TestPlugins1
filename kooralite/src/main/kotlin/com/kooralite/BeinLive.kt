package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URI

class BeinLive : MainAPI() {
    override var mainUrl = "https://www.bein-live.com"
    override var name = "Bein Live - بين لايف"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    
    private val customPosterUrl = "https://raw.githubusercontent.com/kim20598/TestPlugins1/master/beinlive/poster.png"

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

        // Try different selectors for team names
        val team1 = selectFirst(".team1, .home-team, .MT_Team.TM1 .TM_Name, .home_team, .left-team")?.text()?.trim() ?: ""
        val team2 = selectFirst(".team2, .away-team, .MT_Team.TM2 .TM_Name, .away_team, .right-team")?.text()?.trim() ?: ""

        if (team1.isBlank() || team2.isBlank()) return null

        val title = "$team1 vs $team2"
        
        // Try different selectors for match info
        val time = selectFirst(".time, .match-time, .MT_Time, .match_time, .start-time")?.text()?.trim() ?: ""
        val tournament = selectFirst(".tournament, .league, .MT_Info li:last-child span, .competition, .league-name")?.text()?.trim() ?: ""
        
        val poster = customPosterUrl

        // Get status - try multiple class names
        val statusClass = classNames().firstOrNull { it in listOf("live", "finished", "upcoming", "coming-soon", "active", "playing") } ?: ""
        val statusText = when {
            statusClass.contains("live") || classNames().any { it.contains("live") } -> "🔴 مباشر"
            statusClass.contains("finished") || classNames().any { it.contains("finished") } -> "✅ انتهت"
            else -> {
                val txt = selectFirst(".status, .match-status, .MT_Status, .match_status")?.text()?.trim()
                when {
                    txt?.contains("مباشر") == true -> "🔴 مباشر"
                    txt?.contains("انتهت") == true -> "✅ انتهت"
                    txt?.contains("يبدأ") == true -> "⏳ يبدأ"
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
        "$mainUrl/" to "المباريات الحية",
        "$mainUrl/live/" to "البث المباشر",
        "$mainUrl/upcoming/" to "المباريات القادمة",
        "$mainUrl/finished/" to "المباريات المنتهية",
        "$mainUrl/today/" to "مباريات اليوم",
        "$mainUrl/tomorrow/" to "مباريات الغد"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document

        val items = mutableListOf<SearchResponse>()

        // Try different match selectors - common for sports sites
        val selectors = listOf(
            ".match-item",
            ".match",
            ".game",
            ".AY_Match",
            ".live-match",
            "article.match",
            ".match-card",
            ".fixture",
            ".event",
            ".game-item"
        )

        for (selector in selectors) {
            val matches = document.select(selector)
            if (matches.isNotEmpty()) {
                matches.forEach { match ->
                    match.toMatchSearchResponse()?.let { items.add(it) }
                }
                break
            }
        }

        // Fallback: look for any container with match-like structure
        if (items.isEmpty()) {
            document.select("div[class*='match'], div[class*='game'], article").forEach { element ->
                element.toMatchSearchResponse()?.let { items.add(it) }
            }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"

        return try {
            val document = app.get(searchUrl).document
            val results = mutableListOf<SearchResponse>()

            // Try different selectors for search results
            val selectors = listOf(
                ".match-item",
                ".match",
                ".game",
                ".AY_Match",
                "article",
                ".search-result"
            )

            for (selector in selectors) {
                document.select(selector).forEach { match ->
                    match.toMatchSearchResponse()?.let { results.add(it) }
                }
                if (results.isNotEmpty()) break
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
            when {
                status.contains("live") -> append("🔴 الحالة: البث مباشر الآن\n")
                status.contains("finished") -> append("✅ الحالة: انتهت المباراة\n")
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
            
            // Check if it's already a streaming page
            if (dataUrl.contains("stream") || dataUrl.contains("watch") || dataUrl.contains("player")) {
                return extractAllStreams(dataUrl, callback)
            }
            
            val doc = app.get(dataUrl).document
            var foundLinks = false
            
            // Look for iframes and links to streaming pages
            doc.select("iframe").forEach { iframe ->
                val iframeSrc = fixUrl(iframe.attr("src"))
                if (iframeSrc.isNotBlank()) {
                    foundLinks = extractAllStreams(iframeSrc, callback) || foundLinks
                }
            }
            
            // Also look for direct streaming links
            doc.select("a[href*='stream'], a[href*='watch'], a[href*='player']").forEach { link ->
                val linkHref = fixUrl(link.attr("href"))
                if (linkHref.isNotBlank()) {
                    foundLinks = extractAllStreams(linkHref, callback) || foundLinks
                }
            }
            
            // Look for video player divs
            doc.select("div[data-stream], div[data-url]").forEach { div ->
                val streamUrl = div.attr("data-stream") ?: div.attr("data-url")
                if (streamUrl.isNotBlank()) {
                    foundLinks = extractAllStreams(fixUrl(streamUrl), callback) || foundLinks
                }
            }
            
            return foundLinks
            
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun extractAllStreams(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAnyLink = false
        
        try {
            val response = app.get(url)
            val html = response.text
            
            if (html.isBlank()) return false
            
            // Extract base domain for relative URLs
            val baseDomain = try {
                val uri = URI(url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                val domainRegex = Regex("""(https?://[^/]+)""")
                domainRegex.find(url)?.groupValues?.get(1) ?: mainUrl
            }
            
            // =====================================
            // METHOD 1: Look for AlbaPlayerControl (same as KooraLite)
            // =====================================
            val albaPatterns = listOf(
                Regex("""AlbaPlayerControl\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*,\s*['"]hls['"]\s*\)""", setOf(RegexOption.IGNORE_CASE)),
                Regex("""AlbaPlayerControl\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*,\s*['"]plyr['"]\s*\)""", setOf(RegexOption.IGNORE_CASE)),
                Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""", setOf(RegexOption.IGNORE_CASE)),
                Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','plyr'\)""", setOf(RegexOption.IGNORE_CASE))
            )
            
            val processedUrls = mutableSetOf<String>()
            
            for (pattern in albaPatterns) {
                val matches = pattern.findAll(html)
                matches.forEach { match ->
                    val base64String = match.groupValues[1]
                    val decodedUrl = decodeBase64(base64String)
                    if (decodedUrl.isNotBlank() && !processedUrls.contains(decodedUrl)) {
                        processedUrls.add(decodedUrl)
                        
                        val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                        
                        val extractorLink = newExtractorLink(
                            name,
                            "مشغل مباشر",
                            streamUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P720.value
                        }
                        
                        callback.invoke(extractorLink)
                        foundAnyLink = true
                    }
                }
            }
            
            // =====================================
            // METHOD 2: Look for direct m3u8 URLs
            // =====================================
            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", setOf(RegexOption.IGNORE_CASE))
            val m3u8Matches = m3u8Regex.findAll(html)
            
            m3u8Matches.forEach { match ->
                val streamUrl = match.groupValues[1]
                if (streamUrl.isNotBlank() && !processedUrls.contains(streamUrl)) {
                    processedUrls.add(streamUrl)
                    
                    val extractorLink = newExtractorLink(
                        name,
                        "بث مباشر",
                        streamUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P720.value
                    }
                    
                    callback.invoke(extractorLink)
                    foundAnyLink = true
                }
            }
            
            // =====================================
            // METHOD 3: Look for iframe embeds
            // =====================================
            val iframeRegex = Regex("""<iframe[^>]*src\s*=\s*["']([^"']+)["'][^>]*>""", setOf(RegexOption.IGNORE_CASE))
            val iframeMatches = iframeRegex.findAll(html)
            
            for (iframeMatch in iframeMatches) {
                val iframeSrc = iframeMatch.groupValues[1]
                if (iframeSrc.isNotBlank()) {
                    try {
                        val fullUrl = if (!iframeSrc.startsWith("http")) {
                            if (iframeSrc.startsWith("/")) {
                                "$baseDomain$iframeSrc"
                            } else {
                                "$baseDomain/$iframeSrc"
                            }
                        } else {
                            iframeSrc
                        }
                        
                        // Recursively extract from iframe
                        foundAnyLink = extractAllStreams(fullUrl, callback) || foundAnyLink
                    } catch (e: Exception) {
                        // Continue to next iframe
                    }
                }
            }
            
            // =====================================
            // METHOD 4: Look for YouTube embeds
            // =====================================
            val youtubeRegex = Regex("""(?:youtube\.com/embed/|youtu\.be/|youtube\.com/v/)([^&?/\s]+)""", setOf(RegexOption.IGNORE_CASE))
            val youtubeMatches = youtubeRegex.findAll(html)
            
            youtubeMatches.forEach { match ->
                val videoId = match.groupValues[1]
                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                
                if (!processedUrls.contains(youtubeUrl)) {
                    processedUrls.add(youtubeUrl)
                    
                    val extractorLink = newExtractorLink(
                        name,
                        "يوتيوب",
                        youtubeUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                    }
                    
                    callback.invoke(extractorLink)
                    foundAnyLink = true
                }
            }
            
            // =====================================
            // METHOD 5: Look for server selection menu (similar to KooraLite)
            // =====================================
            val serverMenuRegex = Regex("""<div[^>]*class\s*=\s*["'][^"']*server-menu[^"']*["'][^>]*>.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val serverMenuMatch = serverMenuRegex.find(html)
            
            if (serverMenuMatch != null) {
                val menuContent = serverMenuMatch.value
                val serverLinkRegex = Regex("""<a[^>]*href\s*=\s*["']([^"']+)["'][^>]*>([^<]+)</a>""", setOf(RegexOption.IGNORE_CASE))
                val serverMatches = serverLinkRegex.findAll(menuContent)
                
                serverMatches.forEach { serverMatch ->
                    val serverHref = serverMatch.groupValues[1].trim()
                    val serverName = serverMatch.groupValues[2].trim()
                    
                    if (serverName.isNotBlank() && serverHref.isNotBlank()) {
                        try {
                            val fullServerUrl = if (!serverHref.startsWith("http")) {
                                if (serverHref.startsWith("/")) {
                                    "$baseDomain$serverHref"
                                } else {
                                    "$baseDomain/$serverHref"
                                }
                            } else {
                                serverHref
                            }
                            
                            // Process this server page
                            val serverResponse = app.get(fullServerUrl)
                            val serverHtml = serverResponse.text
                            
                            // Look for streams in this server page
                            for (pattern in albaPatterns) {
                                val serverMatches = pattern.findAll(serverHtml)
                                serverMatches.forEach { match ->
                                    val base64String = match.groupValues[1]
                                    val decodedUrl = decodeBase64(base64String)
                                    if (decodedUrl.isNotBlank() && !processedUrls.contains(decodedUrl)) {
                                        processedUrls.add(decodedUrl)
                                        
                                        val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                                        
                                        val extractorLink = newExtractorLink(
                                            name,
                                            serverName,
                                            streamUrl,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = fullServerUrl
                                            this.quality = determineQuality(serverName)
                                        }
                                        
                                        callback.invoke(extractorLink)
                                        foundAnyLink = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Continue to next server
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            // Do nothing
        }
        
        return foundAnyLink
    }
    
    private fun determineQuality(serverName: String): Int {
        return when {
            serverName.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            serverName.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            serverName.contains("hd", ignoreCase = true) -> Qualities.P1080.value
            serverName.contains("رئيسي", ignoreCase = true) -> Qualities.P1080.value
            serverName.contains("720", ignoreCase = true) -> Qualities.P720.value
            serverName.contains("جوال", ignoreCase = true) -> Qualities.P720.value
            serverName.contains("480", ignoreCase = true) -> Qualities.P480.value
            serverName.contains("sd", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.P720.value
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
