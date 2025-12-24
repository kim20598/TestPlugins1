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
    override val supportedTypes = setOf(TvType.Movie)
    
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
                return extractAllAlbaPlayerServers(dataUrl, callback)
            }
            
            val doc = app.get(dataUrl).document
            var foundLinks = false
            
            doc.select("iframe[src*='albaplayer']").forEach { iframe ->
                val iframeSrc = fixUrl(iframe.attr("src"))
                foundLinks = extractAllAlbaPlayerServers(iframeSrc, callback) || foundLinks
            }
            
            return foundLinks
            
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun extractAllAlbaPlayerServers(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAnyLink = false
        
        try {
            // =====================================
            // STEP 1: Get MAIN albaplayer page WITH JAVASCRIPT SUPPORT
            // =====================================
            val response = app.get(url)
            val html = response.text
            
            if (html.isBlank()) return false
            
            // =====================================
            // STEP 2: Extract the base URL dynamically from the HTML
            // =====================================
            val baseDomain = try {
                val uri = URI(url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                // Try to extract from the HTML itself
                val domainRegex = Regex("""(https?://[^/]+)""")
                val match = domainRegex.find(html)
                match?.groupValues?.get(1) ?: "https://b.sia.watch"
            }
            
            // =====================================
            // STEP 3: Extract ALL server data from the HTML
            // =====================================
            // First try: Look for server menu in HTML
            val serverMenuRegex = Regex("""<div[^>]*class\s*=\s*["'][^"']*aplr-menu[^"']*["'][^>]*>.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val serverMenuMatch = serverMenuRegex.find(html)
            
            val servers = mutableListOf<Pair<String, String>>()
            
            if (serverMenuMatch != null) {
                val menuContent = serverMenuMatch.value
                // Extract server links from menu
                val linkRegex = Regex("""<a[^>]*class\s*=\s*["'][^"']*aplr-link[^"']*["'][^>]*href\s*=\s*["']([^"']+)["'][^>]*>([^<]+)</a>""", setOf(RegexOption.IGNORE_CASE))
                val linkMatches = linkRegex.findAll(menuContent)
                
                linkMatches.forEach { match ->
                    val serverHref = match.groupValues[1].trim()
                    val serverName = match.groupValues[2].trim()
                    
                    if (serverName.isNotBlank() && serverHref.isNotBlank()) {
                        val fullUrl = if (!serverHref.startsWith("http")) {
                            if (serverHref.startsWith("/")) {
                                "$baseDomain$serverHref"
                            } else {
                                "$baseDomain/$serverHref"
                            }
                        } else {
                            serverHref
                        }
                        servers.add(Pair(serverName, fullUrl))
                    }
                }
            }
            
            // Fallback: Try to extract servers from any script
            if (servers.isEmpty()) {
                // Look for servers in JavaScript variables
                val serverRegex = Regex("""['"]?server['"]?\s*:\s*['"]([^'"]+)['"]""", setOf(RegexOption.IGNORE_CASE))
                val serverMatches = serverRegex.findAll(html)
                
                serverMatches.forEachIndexed { index, match ->
                    val serverValue = match.groupValues[1]
                    if (serverValue.isNotBlank()) {
                        // Try to construct server URL
                        val serverUrl = if (serverValue.contains("?")) {
                            if (serverValue.startsWith("/")) {
                                "$baseDomain$serverValue"
                            } else if (!serverValue.startsWith("http")) {
                                "$baseDomain/$serverValue"
                            } else {
                                serverValue
                            }
                        } else {
                            // Add serv parameter
                            "$url?serv=${index + 1}"
                        }
                        servers.add(Pair("Server ${index + 1}", serverUrl))
                    }
                }
            }
            
            // If still no servers, try the default serv parameters
            if (servers.isEmpty()) {
                // Try common server patterns
                for (i in 1..4) {
                    servers.add(Pair("Server $i", "$url?serv=$i"))
                }
            }
            
            // =====================================
            // STEP 4: Process EACH server page
            // =====================================
            val processedUrls = mutableSetOf<String>()
            
            // Process the current page first (might be main stream)
            processAlbaPlayerPage(url, "Main Stream", html, callback, processedUrls)?.let {
                foundAnyLink = true
            }
            
            // Process each server page
            for ((serverName, serverUrl) in servers) {
                try {
                    val serverResponse = app.get(serverUrl)
                    val serverHtml = serverResponse.text
                    
                    processAlbaPlayerPage(serverUrl, serverName, serverHtml, callback, processedUrls)?.let {
                        foundAnyLink = true
                    }
                } catch (e: Exception) {
                    // Try with different headers
                    try {
                        val serverResponse = app.get(serverUrl, headers = mapOf(
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to "en-US,en;q=0.9",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        ))
                        val serverHtml = serverResponse.text
                        
                        processAlbaPlayerPage(serverUrl, serverName, serverHtml, callback, processedUrls)?.let {
                            foundAnyLink = true
                        }
                    } catch (e2: Exception) {
                        // Continue to next server
                    }
                }
            }
            
        } catch (e: Exception) {
            // Do nothing
        }
        
        return foundAnyLink
    }
    
    private suspend fun processAlbaPlayerPage(
        pageUrl: String,
        serverName: String,
        html: String,
        callback: (ExtractorLink) -> Unit,
        processedUrls: MutableSet<String>
    ): Boolean {
        var foundLink = false
        
        try {
            // =====================================
            // METHOD 1: Look for AlbaPlayerControl in the HTML
            // =====================================
            val regexPatterns = listOf(
                Regex("""AlbaPlayerControl\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*,\s*['"]hls['"]\s*\)""", setOf(RegexOption.IGNORE_CASE)),
                Regex("""AlbaPlayerControl\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*,\s*['"]plyr['"]\s*\)""", setOf(RegexOption.IGNORE_CASE)),
                Regex("""AlbaPlayerControl\s*\(\s*"([A-Za-z0-9+/=]+)"\s*,\s*"hls"\s*\)""", setOf(RegexOption.IGNORE_CASE)),
                Regex("""AlbaPlayerControl\s*\(\s*"([A-Za-z0-9+/=]+)"\s*,\s*"plyr"\s*\)""", setOf(RegexOption.IGNORE_CASE)),
                Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""", setOf(RegexOption.IGNORE_CASE)),
                Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','plyr'\)""", setOf(RegexOption.IGNORE_CASE))
            )
            
            var base64String: String? = null
            for (pattern in regexPatterns) {
                val match = pattern.find(html)
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
                    
                    // Assign quality based on server name
                    val quality = when {
                        serverName.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                        serverName.contains("hd", ignoreCase = true) -> Qualities.P1080.value
                        serverName.contains("رئيسي", ignoreCase = true) -> Qualities.P1080.value
                        serverName.contains("sd", ignoreCase = true) -> Qualities.P480.value
                        serverName.contains("جوال", ignoreCase = true) -> Qualities.P720.value
                        serverName.contains("english", ignoreCase = true) -> Qualities.P1080.value
                        serverName.contains("احتياطي", ignoreCase = true) -> Qualities.P720.value
                        else -> Qualities.P720.value
                    }
                    
                    val extractorLink = newExtractorLink(
                        name,
                        "$serverName",
                        streamUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.quality = quality
                    }
                    
                    callback.invoke(extractorLink)
                    foundLink = true
                }
            }
            
            // =====================================
            // METHOD 2: Look for direct m3u8 URLs in the HTML
            // =====================================
            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", setOf(RegexOption.IGNORE_CASE))
            val m3u8Matches = m3u8Regex.findAll(html)
            
            for (match in m3u8Matches) {
                val streamUrl = match.groupValues[1]
                if (streamUrl.isNotBlank() && !processedUrls.contains(streamUrl)) {
                    processedUrls.add(streamUrl)
                    
                    val extractorLink = newExtractorLink(
                        name,
                        "$serverName (Direct)",
                        streamUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.P720.value
                    }
                    
                    callback.invoke(extractorLink)
                    foundLink = true
                }
            }
            
            // =====================================
            // METHOD 3: Look for iframe embeds
            // =====================================
            val iframeRegex = Regex("""<iframe[^>]*src\s*=\s*["']([^"']+)["'][^>]*>""", setOf(RegexOption.IGNORE_CASE))
            val iframeMatches = iframeRegex.findAll(html)
            
            for (match in iframeMatches) {
                val iframeSrc = match.groupValues[1]
                if (iframeSrc.isNotBlank() && iframeSrc.contains("albaplayer")) {
                    try {
                        val fullUrl = if (!iframeSrc.startsWith("http")) {
                            val baseDomain = try {
                                val uri = URI(pageUrl)
                                "${uri.scheme}://${uri.host}"
                            } catch (e: Exception) {
                                "https://b.sia.watch"
                            }
                            if (iframeSrc.startsWith("/")) {
                                "$baseDomain$iframeSrc"
                            } else {
                                "$baseDomain/$iframeSrc"
                            }
                        } else {
                            iframeSrc
                        }
                        
                        // Recursively process iframe
                        foundLink = extractAllAlbaPlayerServers(fullUrl, callback) || foundLink
                    } catch (e: Exception) {
                        // Continue to next iframe
                    }
                }
            }
            
        } catch (e: Exception) {
            // Do nothing
        }
        
        return foundLink
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
