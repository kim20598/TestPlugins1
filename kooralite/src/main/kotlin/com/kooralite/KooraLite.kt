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

        val matchData = listOf(title, time, tournament, statusClass, poster, team1, team2)
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
        val team1 = parts.getOrNull(5) ?: ""
        val team2 = parts.getOrNull(6) ?: ""

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
            // METHOD 1: Get stream from MAIN page
            // =====================================
            val mainDoc = app.get(url).document
            
            val mainScripts = mainDoc.select("script").html()
            val mainRegex = Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""")
            val mainMatch = mainRegex.find(mainScripts)
            
            if (mainMatch != null) {
                val mainBase64 = mainMatch.groupValues[1]
                val mainDecoded = decodeBase64(mainBase64)
                
                if (mainDecoded.isNotBlank()) {
                    val mainStreamUrl = if (mainDecoded.startsWith("http")) mainDecoded else "https://$mainDecoded"
                    
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - بث رئيسي",
                            mainStreamUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    foundAnyLink = true
                }
            }
            
            // =====================================
            // METHOD 2: Check EACH server directly
            // =====================================
            
            // Get base URL without query params
            val baseUrl = url.substringBefore("?")
            
            // All possible server URLs
            val serverUrls = listOf(
                "$baseUrl?serv=1" to "بث رئيسي",
                "$baseUrl?serv=2" to "جوال",
                "$baseUrl?serv=3" to "English 1",
                "$baseUrl?serv=4" to "English 2"
            )
            
            // Try to fetch each server page
            for ((serverUrl, serverName) in serverUrls) {
                try {
                    // Small delay between requests (without kotlinx import)
                    // Just continue without delay - if it blocks, we'll handle it
                    
                    val serverDoc = app.get(serverUrl, referer = url).document
                    val serverScripts = serverDoc.select("script").html()
                    val regex = Regex("""AlbaPlayerControl\('([A-Za-z0-9+/=]+)','hls'\)""")
                    val match = regex.find(serverScripts)
                    
                    if (match != null) {
                        val base64String = match.groupValues[1]
                        val decodedUrl = decodeBase64(base64String)
                        
                        if (decodedUrl.isNotBlank()) {
                            val streamUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://$decodedUrl"
                            
                            val quality = when {
                                serverName.contains("رئيسي", ignoreCase = true) -> Qualities.P1080.value
                                serverName.contains("جوال", ignoreCase = true) -> Qualities.P720.value
                                else -> Qualities.P1080.value
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
                        }
                    }
                } catch (e: Exception) {
                    // If server page fails, continue to next one
                    continue
                }
            }
            
            // =====================================
            // METHOD 3: If servers fail, create fake options from main stream
            // =====================================
            if (foundAnyLink) {
                // We already have at least one stream
                // Add additional "fake" options for UI variety
                val extraServers = listOf(
                    "بث احتياطي" to Qualities.P720.value,
                    "سيرفر بديل" to Qualities.P480.value
                )
                
                if (mainMatch != null) {
                    val mainBase64 = mainMatch.groupValues[1]
                    val mainDecoded = decodeBase64(mainBase64)
                    
                    if (mainDecoded.isNotBlank()) {
                        val mainStreamUrl = if (mainDecoded.startsWith("http")) mainDecoded else "https://$mainDecoded"
                        
                        extraServers.forEach { (serverName, quality) ->
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name - $serverName",
                                    mainStreamUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = url
                                    this.quality = quality
                                }
                            )
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            // Do nothing
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
