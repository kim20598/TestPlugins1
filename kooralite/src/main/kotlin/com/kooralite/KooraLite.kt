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
    
    // Custom poster image for all matches
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
        
        val team1LogoRaw = selectFirst(".MT_Team.TM1 .TM_Logo img")?.attr("src")
        val team2LogoRaw = selectFirst(".MT_Team.TM2 .TM_Logo img")?.attr("src")
        val team1Logo = team1LogoRaw?.let { if (it.startsWith("http")) it else fixUrl(it) }
        val team2Logo = team2LogoRaw?.let { if (it.startsWith("http")) it else fixUrl(it) }
        
        // Always use custom poster instead of team logos
        val poster = customPosterUrl

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
        val matchData = listOf(title, time, tournament, statusClass, poster, team1, team2, team1Logo ?: "", team2Logo ?: "")
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
        val poster = customPosterUrl // Always use custom poster
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
            
            // If it's already an albaplayer URL, extract all content
            if (dataUrl.contains("albaplayer")) {
                return extractAllContent(dataUrl, callback)
            }
            
            // Otherwise, find albaplayer iframes
            val doc = app.get(dataUrl).document
            var foundLinks = false
            
            // Look for albaplayer iframes
            doc.select("iframe[src*='albaplayer']").forEach { iframe ->
                val iframeSrc = fixUrl(iframe.attr("src"))
                foundLinks = extractAllContent(iframeSrc, callback) || foundLinks
            }
            
            // Also look for iframes with albaplayer in the parent page
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
            // =====================================
            // STEP 1: Get MAIN page
            // =====================================
            val mainDoc = app.get(url).document
            
            // =====================================
            // STEP 2: Extract the base domain from the current URL
            // =====================================
            val baseDomain = try {
                val uri = URI(url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                // Fallback: try to extract domain from URL
                val domainRegex = Regex("""(https?://[^/]+)""")
                domainRegex.find(url)?.groupValues?.get(1) ?: "https://lp.kooralive.cfd"
            }
            
            // =====================================
            // STEP 3: Extract ALL server links from the menu
            // =====================================
            val serverLinks = mainDoc.select(".aplr-menu a.aplr-link")
            val servers = mutableListOf<Pair<String, String>>()
            
            serverLinks.forEach { link ->
                val serverName = link.text().trim()
                var serverHref = link.attr("href")
                
                if (serverName.isNotBlank() && serverHref.isNotBlank()) {
                    // Fix the URL if needed
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
            
            // =====================================
            // STEP 4: Process EACH server to get its stream
            // =====================================
            val processedUrls = mutableSetOf<String>()
            
            // Process the current page first
            processPageStream(url, "Current Page Stream", mainDoc, callback, processedUrls)?.let {
                foundAnyLink = true
            }
            
            // Process each server page
            for ((serverName, serverUrl) in servers) {
                try {
                    // Fetch THIS server's page
                    val serverDoc = app.get(serverUrl).document
                    
                    processPageStream(serverUrl, serverName, serverDoc, callback, processedUrls)?.let {
                        foundAnyLink = true
                    }
                    
                } catch (e: Exception) {
                    // Continue to next server
                }
            }
            
            // =====================================
            // STEP 5: Look for YouTube embeds
            // =====================================
            val youtubeIframes = mainDoc.select("iframe[src*='youtube.com'], iframe[src*='youtu.be']")
            for (iframe in youtubeIframes) {
                val youtubeSrc = iframe.attr("src")
                if (youtubeSrc.isNotBlank()) {
                    // Extract YouTube video ID
                    val videoIdRegex = Regex("""(?:youtube\.com\/embed\/|youtu\.be\/|youtube\.com\/v\/|youtube\.com\/watch\?v=)([^&?/\s]+)""")
                    val match = videoIdRegex.find(youtubeSrc)
                    match?.let {
                        val videoId = it.groupValues[1]
                        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                        
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name - YouTube",
                                youtubeUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P720.value
                            }
                        )
                        foundAnyLink = true
                    }
                }
            }
            
            // =====================================
            // STEP 6: Look for iframe embeds
            // =====================================
            val otherIframes = mainDoc.select("iframe:not([src*='youtube.com']):not([src*='youtu.be'])")
            for (iframe in otherIframes) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    try {
                        val fullIframeUrl = if (!iframeSrc.startsWith("http")) {
                            if (iframeSrc.startsWith("/")) {
                                "$baseDomain$iframeSrc"
                            } else {
                                "$baseDomain/$iframeSrc"
                            }
                        } else {
                            iframeSrc
                        }
                        
                        // Recursively process iframe content
                        if (fullIframeUrl.contains("albaplayer")) {
                            foundAnyLink = extractAllContent(fullIframeUrl, callback) || foundAnyLink
                        }
                    } catch (e: Exception) {
                        // Continue to next iframe
                    }
                }
            }
            
        } catch (e: Exception) {
            // Do nothing
        }
        
        return foundAnyLink
    }
    
    private suspend fun processPageStream(  // Changed to suspend function
        pageUrl: String,
        serverName: String,
        doc: org.jsoup.nodes.Document,
        callback: (ExtractorLink) -> Unit,
        processedUrls: MutableSet<String>
    ): Boolean {
        var foundLink = false
        
        try {
            // Look for AlbaPlayerControl in scripts
            val scripts = doc.select("script").html()
            
            // Try to find AlbaPlayerControl with different patterns
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
                    
                    // Assign quality based on server name
                    val quality = when {
                        serverName.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                        serverName.contains("hd", ignoreCase = true) -> Qualities.P1080.value
                        serverName.contains("رئيسي", ignoreCase = true) -> Qualities.P1080.value
                        serverName.contains("sd", ignoreCase = true) -> Qualities.P480.value
                        serverName.contains("جوال", ignoreCase = true) -> Qualities.P720.value
                        serverName.contains("english", ignoreCase = true) -> Qualities.P1080.value
                        serverName.contains("احتياطي", ignoreCase = true) -> Qualities.P720.value
                        serverName.contains("live", ignoreCase = true) -> Qualities.P720.value
                        serverName.contains("مشغل", ignoreCase = true) -> {
                            when {
                                serverName.contains("hd", ignoreCase = true) -> Qualities.P1080.value
                                serverName.contains("sd", ignoreCase = true) -> Qualities.P480.value
                                else -> Qualities.P720.value
                            }
                        }
                        else -> Qualities.Unknown.value
                    }
                    
                    // Now this is called from a suspend function, so it's valid
                    val extractorLink = newExtractorLink(
                        name,
                        "$name - $serverName",
                        streamUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl  // CRITICAL for AWS S3 access
                        this.quality = quality
                    }
                    
                    callback.invoke(extractorLink)
                    foundLink = true
                }
            }
            
            // Also check for direct m3u8 links in the page
            val directM3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            val allText = doc.html()
            val m3u8Matches = directM3u8Regex.findAll(allText)
            
            for (match in m3u8Matches) {
                val streamUrl = match.groupValues[1]
                if (streamUrl.contains("m3u8") && !processedUrls.contains(streamUrl)) {
                    processedUrls.add(streamUrl)
                    
                    // Now this is called from a suspend function, so it's valid
                    val extractorLink = newExtractorLink(
                        name,
                        "$name - $serverName (Direct)",
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
