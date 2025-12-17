package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "Animesuge"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime, 
        TvType.AnimeMovie, 
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Recently Updated",
        "$mainUrl/new-release" to "New Releases", 
        "$mainUrl/most-viewed" to "Popular Anime",
        "$mainUrl/status/finished-airing" to "Completed",
        "$mainUrl/status/currently-airing" to "Ongoing"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + "?page=$page").document
        val home = document.select(".anime.main-card .item, .anime.mini-card .item, a.item[href*='/watch/']")
            .mapNotNull {
                val title = it.selectFirst(".name, .detail .name, .item-bottom .name")?.text()?.trim() ?: return@mapNotNull null
                val href = fixUrl(it.attr("href"))
                val image = it.selectFirst("img")?.attr("src")?.let { img -> fixUrlNull(img) }
                
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = image
                }
            }
        
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/filter?keyword=$encodedQuery").document
        
        return document.select(".anime.main-card .item, .anime.mini-card .item, a.item[href*='/watch/']")
            .mapNotNull {
                val title = it.selectFirst(".name, .detail .name, .item-bottom .name")?.text()?.trim() ?: return@mapNotNull null
                val href = fixUrl(it.attr("href"))
                val image = it.selectFirst("img")?.attr("src")?.let { img -> fixUrlNull(img) }
                
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = image
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Get title
        val title = document.selectFirst("h1.title, .title, h1")?.text()?.trim() ?: "Unknown"
        
        // Get poster
        val poster = document.selectFirst("#media-info .poster img, [itemprop=image], img.poster")?.attr("src")?.let { fixUrlNull(it) }
        
        // Get plot
        val plot = document.selectFirst(".description, .plot, [itemprop=description]")?.text()?.trim()
        
        // Get episodes
        val episodes = mutableListOf<Episode>()
        val episodeElements = document.select("#media-episode a[href*='/watch/'], .range a[href*='/watch/']")
        
        for (ep in episodeElements) {
            val episodeUrl = fixUrl(ep.attr("href"))
            val episodeNumber = ep.attr("data-slug").toIntOrNull() ?: 
                               ep.text().trim().toIntOrNull() ?: 
                               Regex("""ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            
            val episodeName = ep.attr("title").ifBlank { 
                ep.attr("data-num").ifBlank { 
                    "Episode $episodeNumber" 
                }
            }
            
            episodes.add(
                newEpisode(episodeUrl) {
                    name = episodeName
                    this.episode = episodeNumber
                }
            )
        }
        
        // Check if it's a movie
        val type = document.selectFirst(".meta div:contains(Type) + span")?.text()?.trim()?.lowercase()
        val isMovie = type?.contains("movie") == true || url.contains("/movie/") || episodes.isEmpty()
        
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodeElements.firstOrNull()?.attr("href") ?: url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false
        
        // Method 1: Try to find servers
        val servers = document.select(".server")
        for (server in servers) {
            val serverName = server.selectFirst("span")?.text()?.trim() ?: continue
            val dataLinkId = server.attr("data-link-id")
            
            if (dataLinkId.isNotBlank()) {
                // Try different URL patterns based on server name
                val possibleUrls = mutableListOf<String>()
                
                when {
                    serverName.contains("Megaplay", true) -> {
                        possibleUrls.add("https://megaplay.buzz/stream/s-1/$dataLinkId?autostart=true")
                    }
                    serverName.contains("Vidstream", true) -> {
                        possibleUrls.add("https://vidstream.pro/e/$dataLinkId")
                        possibleUrls.add("https://vidstream.to/e/$dataLinkId")
                    }
                    serverName.contains("VidCloud", true) -> {
                        possibleUrls.add("https://vidcloud.pro/e/$dataLinkId")
                    }
                    serverName.contains("Kiwi", true) -> {
                        possibleUrls.add("https://kiwistream.pro/player/$dataLinkId")
                    }
                }
                
                // Also try base64 decoding
                try {
                    val decoded = Base64.getDecoder().decode(dataLinkId)
                    val decodedString = String(decoded)
                    if (decodedString.startsWith("http")) {
                        possibleUrls.add(decodedString)
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                
                // Try each URL
                for (url in possibleUrls) {
                    if (loadExtractor(url, subtitleCallback, callback)) {
                        foundLinks = true
                        break
                    }
                }
                
                if (foundLinks) break
            }
        }
        
        // Method 2: Try iframe
        if (!foundLinks) {
            val iframe = document.selectFirst("iframe[src]")
            val iframeSrc = iframe?.attr("src")?.let { fixUrl(it) }
            if (iframeSrc != null) {
                foundLinks = loadExtractor(iframeSrc, subtitleCallback, callback)
            }
        }
        
        // Method 3: Try to extract from scripts
        if (!foundLinks) {
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptText = script.html()
                // Look for common video patterns
                val patterns = listOf(
                    Regex("""src\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                    Regex("""file\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                    Regex("""(https?://[^'"\s]+\.(mp4|m3u8|webm))""")
                )
                
                for (pattern in patterns) {
                    val matches = pattern.findAll(scriptText)
                    for (match in matches) {
                        val url = match.groupValues[1]
                        if (url.contains("video") || url.contains(".mp4") || url.contains(".m3u8")) {
                            if (loadExtractor(url, subtitleCallback, callback)) {
                                foundLinks = true
                                break
                            }
                        }
                    }
                    if (foundLinks) break
                }
                if (foundLinks) break
            }
        }
        
        return foundLinks
    }
}
