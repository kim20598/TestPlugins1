package com.kooralite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KooraLite : MainAPI() {
    override var mainUrl = "https://www.fullmatch-hd.com"
    override var name = "KooraLite - كورة لايت"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

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
        
        val team1LogoRaw = selectFirst(".MT_Team.TM1 .TM_Logo img")?.attr("src")
        val team2LogoRaw = selectFirst(".MT_Team.TM2 .TM_Logo img")?.attr("src")
        val team1Logo = team1LogoRaw?.let { if (it.startsWith("http")) it else fixUrl(it) }
        val team2Logo = team2LogoRaw?.let { if (it.startsWith("http")) it else fixUrl(it) }
        val poster = team1Logo ?: team2Logo

        // Get status
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

        // Store match data
        val matchData = listOf(title, time, tournament, statusClass, poster ?: "", team1, team2, team1Logo ?: "", team2Logo ?: "")
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

        // Get matches
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
        val poster = parts.getOrNull(5)
        val team1 = parts.getOrNull(6) ?: ""
        val team2 = parts.getOrNull(7) ?: ""

        val document = app.get(actualUrl).document

        // Build simple description
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
            val doc = app.get(dataUrl).document
            var foundLinks = false
            
            // Look for ANY iframe with albaplayer
            doc.select("iframe[src*='albaplayer']").forEach { iframe ->
                val iframeSrc = fixUrl(iframe.attr("src"))
                foundLinks = extractAllAlbaPlayerServers(iframeSrc, callback) || foundLinks
            }
            
            // Also check for direct albaplayer links in the page content
            doc.select("a[href*='albaplayer']").forEach { link ->
                val linkHref = fixUrl(link.attr("href"))
                foundLinks = extractAllAlbaPlayerServers(linkHref, callback) || foundLinks
            }
            
            return foundLinks
            
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun extractAllAlbaPlayerServers(
        baseUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAnyLink = false
        
        try {
            // =====================================
            // STEP 1: Get the main albaplayer page
            // =====================================
            val mainDoc = app.get(baseUrl).document
            
            // =====================================
            // STEP 2: Find ALL server links from the menu
            // =====================================
            val servers = mutableListOf<Pair<String, String>>()
            
            // Look for server links in the aplr-menu
            mainDoc.select(".aplr-menu a.aplr-link").forEach { link ->
                val serverName = link.text().trim()
                var serverUrl = link.attr("href")
                
                if (serverName.isNotBlank() && serverUrl.isNotBlank()) {
                    // Fix URL if needed
                    if (!serverUrl.startsWith("http")) {
                        serverUrl = if (serverUrl.startsWith("/")) {
                            "https://b.sia.watch$serverUrl"
                        } else {
                            "$baseUrl$serverUrl"
                        }
                    }
                    servers.add(Pair(serverName, serverUrl))
                }
            }
            
            // If no servers found in menu, use the base URL as default server
            if (servers.isEmpty()) {
                servers.add(Pair("بث رئيسي", baseUrl))
            }
            
            println("DEBUG: Found ${servers.size} servers: ${servers.map { it.first }}")
            
            // =====================================
            // STEP 3: Try each server URL
            // =====================================
            for ((serverName, serverUrl) in servers) {
                try {
                    println("DEBUG: Trying server: $serverName -> $serverUrl")
                    
                    // Fetch this server's page
                    val serverDoc = app.get(serverUrl).document
                    
                    // Look for AlbaPlayerControl in scripts
                    val scripts = serverDoc.select("script").toString()
                    val regex = Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""")
                    val match = regex.find(scripts)
                    
                    if (match != null) {
                        val base64String = match.groupValues[1]
                        val decodedUrl = decodeBase64(base64String)
                        
                        if (decodedUrl.isNotBlank()) {
                            val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                            
                            println("DEBUG: Success! Found stream for $serverName: $streamUrl")
                            
                            // Assign quality based on server name
                            val quality = when {
                                serverName.contains("رئيسي", ignoreCase = true) -> Qualities.P1080.value
                                serverName.contains("جوال", ignoreCase = true) -> Qualities.P720.value
                                serverName.contains("720", ignoreCase = true) -> Qualities.P720.value
                                serverName.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                                serverName.contains("hd", ignoreCase = true) -> Qualities.P1080.value
                                serverName.contains("high", ignoreCase = true) -> Qualities.P1080.value
                                else -> Qualities.Unknown.value
                            }
                            
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name - $serverName",
                                    streamUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = serverUrl
                                    this.quality = quality
                                }
                            )
                            foundAnyLink = true
                        } else {
                            println("DEBUG: Failed to decode base64 for $serverName")
                        }
                    } else {
                        println("DEBUG: No AlbaPlayerControl found in $serverName")
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error processing server $serverName: ${e.message}")
                    continue
                }
            }
            
            // =====================================
            // STEP 4: Try to find stream in main page as fallback
            // =====================================
            if (!foundAnyLink) {
                try {
                    val mainScripts = mainDoc.select("script").toString()
                    val regex = Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""")
                    val match = regex.find(mainScripts)
                    
                    if (match != null) {
                        val base64String = match.groupValues[1]
                        val decodedUrl = decodeBase64(base64String)
                        
                        if (decodedUrl.isNotBlank()) {
                            val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                            
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name - بث رئيسي",
                                    streamUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = baseUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            foundAnyLink = true
                        }
                    }
                } catch (e: Exception) {
                    println("DEBUG: Fallback extraction failed: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            println("DEBUG: Error in extractAllAlbaPlayerServers: ${e.message}")
        }
        
        return foundAnyLink
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
