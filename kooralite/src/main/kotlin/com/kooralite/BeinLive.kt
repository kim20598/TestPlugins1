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

        // EXACT selectors from the HTML
        val team1 = selectFirst(".MT_Team.TM1 .TM_Name")?.text()?.trim() ?: ""
        val team2 = selectFirst(".MT_Team.TM2 .TM_Name")?.text()?.trim() ?: ""

        if (team1.isBlank() || team2.isBlank()) return null

        val title = "$team1 vs $team2"
        
        // EXACT selectors from the HTML
        val time = selectFirst(".MT_Time")?.text()?.trim() ?: ""
        val tournament = selectFirst(".MT_Info li:last-child span")?.text()?.trim() ?: ""
        
        val poster = customPosterUrl

        // Get status from class names (EXACT as in HTML)
        val statusClass = classNames().firstOrNull { it in listOf("live", "finished", "comming-soon", "not-started") } ?: ""
        val statusText = when (statusClass) {
            "live" -> "🔴 مباشر"
            "finished" -> "✅ انتهت"
            "comming-soon" -> "⏳ قادم"
            "not-started" -> "⏳ لم تبدأ"
            else -> {
                // Fallback: check MT_Stat text
                val statText = selectFirst(".MT_Stat")?.text()?.trim()
                when {
                    statText?.contains("جارية") == true -> "🔴 مباشر"
                    statText?.contains("انتهت") == true -> "✅ انتهت"
                    statText?.contains("قريب") == true -> "⏳ قادم"
                    else -> "⏳ قادم"
                }
            }
        }

        val enhancedTitle = if (time.isNotBlank()) {
            "$statusText $title ($time)"
        } else {
            "$statusText $title"
        }

        // Get broadcast channel (first li in MT_Info)
        val broadcast = selectFirst(".MT_Info li:first-child span")?.text()?.trim() ?: ""

        val matchData = listOf(title, time, tournament, statusClass, poster, team1, team2, broadcast, "")
            .joinToString("|")
        val dataUrl = "$href|$matchData"

        return newMovieSearchResponse(enhancedTitle, dataUrl, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // UPDATED main page URLs based on actual HTML
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/matches-today_1/" to "مباريات اليوم",  // Changed from /matches-today/
        "$mainUrl/matches-yesterday/" to "مباريات الأمس",
        "$mainUrl/matches-tomorrow/" to "مباريات الغد",
        "$mainUrl/home_1/" to "المباريات الحية"  // Alternative home page
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document

        val items = mutableListOf<SearchResponse>()

        // EXACT selector from HTML: .AY_Match inside .albaflex
        document.select(".albaflex .AY_Match, .AY_Match").forEach { match ->
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

            // Search for matches in search results
            document.select(".AY_Match, .match-item, article").forEach { match ->
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
        val broadcast = parts.getOrNull(8) ?: ""

        val description = buildString {
            if (team1.isNotBlank() && team2.isNotBlank()) {
                append("⚽ $team1 vs $team2\n")
            }
            if (time.isNotBlank()) {
                append("🕒 الوقت: $time\n")
            }
            if (broadcast.isNotBlank()) {
                append("📺 القناة: $broadcast\n")
            }
            if (tournament.isNotBlank()) {
                append("🏆 البطولة: $tournament\n")
            }
            when (status) {
                "live" -> append("🔴 الحالة: البث مباشر الآن\n")
                "finished" -> append("✅ الحالة: انتهت المباراة\n")
                "comming-soon" -> append("⏳ الحالة: قادمة قريباً\n")
                "not-started" -> append("⏳ الحالة: لم تبدأ بعد\n")
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
            
            // Check for albaplayer iframes (same as KooraLite)
            val doc = app.get(dataUrl).document
            var foundLinks = false
            
            // Look for albaplayer iframes
            doc.select("iframe[src*='albaplayer']").forEach { iframe ->
                val iframeSrc = fixUrl(iframe.attr("src"))
                foundLinks = extractAllAlbaPlayerStreams(iframeSrc, callback) || foundLinks
            }
            
            // Look for video-serv links (streaming server links)
            doc.select(".video-serv a").forEach { link ->
                val linkHref = fixUrl(link.attr("href"))
                if (linkHref.isNotBlank()) {
                    foundLinks = extractAllAlbaPlayerStreams(linkHref, callback) || foundLinks
                }
            }
            
            // Look for direct streaming iframes
            doc.select("iframe[src*='stream'], iframe[src*='watch']").forEach { iframe ->
                val iframeSrc = fixUrl(iframe.attr("src"))
                if (iframeSrc.isNotBlank()) {
                    foundLinks = extractAllAlbaPlayerStreams(iframeSrc, callback) || foundLinks
                }
            }
            
            return foundLinks
            
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun extractAllAlbaPlayerStreams(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAnyLink = false
        
        try {
            val response = app.get(url)
            val html = response.text
            
            if (html.isBlank()) return false
            
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
            // METHOD 3: Look for server selection (same as KooraLite)
            // =====================================
            // Check for .aplr-menu (albaplayer server menu)
            val serverMenuRegex = Regex("""<div[^>]*class\s*=\s*["'][^"']*aplr-menu[^"']*["'][^>]*>.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val serverMenuMatch = serverMenuRegex.find(html)
            
            if (serverMenuMatch != null) {
                val menuContent = serverMenuMatch.value
                val serverLinkRegex = Regex("""<a[^>]*class\s*=\s*["'][^"']*aplr-link[^"']*["'][^>]*href\s*=\s*["']([^"']+)["'][^>]*>([^<]+)</a>""", setOf(RegexOption.IGNORE_CASE))
                val serverMatches = serverLinkRegex.findAll(menuContent)
                
                // Extract base domain
                val baseDomain = try {
                    val uri = URI(url)
                    "${uri.scheme}://${uri.host}"
                } catch (e: Exception) {
                    "https://b.sia.watch"  // Common albaplayer domain
                }
                
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
                            
                            // Process this server page recursively
                            foundAnyLink = extractAllAlbaPlayerStreams(fullServerUrl, callback) || foundAnyLink
                        } catch (e: Exception) {
                            // Continue to next server
                        }
                    }
                }
            }
            
            // =====================================
            // METHOD 4: Look for YouTube embeds
            // =====================================
            val youtubeRegex = Regex("""(?:youtube\.com/embed/|youtu\.be/)([^&?/\s]+)""", setOf(RegexOption.IGNORE_CASE))
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
