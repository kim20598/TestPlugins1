package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "AnimeSuge"
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

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".name, .detail .name, h3, h4")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("data-src")
        )?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val url = request.data + if (page > 1) "?page=$page" else ""
            val document = app.get(url, headers = getHeaders()).document
            
            val home = document.select(".item, .anime-card, .card, .anime-poster, .poster, article")
                .mapNotNull { it.toSearchResult() }
            
            return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = true)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val document = app.get("$mainUrl/filter?keyword=$encodedQuery", headers = getHeaders()).document
            
            document.select(".item, .anime-card, .card, .anime-poster, .poster, article")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, headers = getHeaders()).document
            
            val title = document.selectFirst("h1.title, h1, .title")?.text()?.trim() ?: "Unknown Title"
            val poster = document.selectFirst(".poster img, [itemprop=image], .cover img")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val plot = document.selectFirst(".description, .plot, .summary")?.text()?.trim()
            
            // Extract anime ID from the page
            val animeId = extractAnimeId(document, url)
            
            // Check if it's a movie
            val typeText = document.selectFirst(".meta div:contains(Type) + span")?.text()?.trim()
            val isMovie = typeText?.contains("movie", true) == true || 
                         url.contains("/movie/", ignoreCase = true)
            
            val episodes = mutableListOf<Episode>()
            
            if (!isMovie && animeId != null) {
                // Get episode count
                val episodeCount = getEpisodeCount(document)
                
                if (episodeCount > 0) {
                    // Create episodes with anime ID and episode number as data
                    for (i in 1..episodeCount) {
                        val episodeData = "$animeId|$i"
                        
                        episodes.add(
                            newEpisode(episodeData) {
                                name = "Episode $i"
                                this.episode = i
                            }
                        )
                    }
                }
            }
            
            if (isMovie || episodes.isEmpty()) {
                // For movies, use anime ID or URL as data
                val movieData = animeId ?: url
                newMovieLoadResponse(title, movieData, TvType.AnimeMovie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            
        } catch (e: Exception) {
            newMovieLoadResponse("Error", url, TvType.AnimeMovie, url) {
                this.plot = "Failed to load: ${e.message}"
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
            // data format: "animeId|episodeNumber" for series, or "animeId/url" for movies
            if (data.contains("|")) {
                // Series episode
                val parts = data.split("|")
                if (parts.size >= 2) {
                    val animeId = parts[0]
                    val episodeNum = parts[1].toIntOrNull() ?: 1
                    
                    // Get episode servers via AJAX
                    return getEpisodeServers(animeId, episodeNum, subtitleCallback, callback)
                }
            } else {
                // Movie - try to get direct movie servers
                val animeId = data
                return getMovieServers(animeId, subtitleCallback, callback)
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun getEpisodeServers(
        animeId: String,
        episodeNum: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // First get the episode ID from the anime ID
            val episodeListUrl = "$mainUrl/ajax/v2/episode/list/$animeId"
            val episodeListResponse = app.get(episodeListUrl, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to mainUrl
            ))
            
            if (episodeListResponse.isSuccessful) {
                val episodeDoc = episodeListResponse.document
                // Find the specific episode element
                val episodeElement = episodeDoc.select("a[data-number=\"$episodeNum\"], a[data-episode=\"$episodeNum\"], li[data-number=\"$episodeNum\"]").first()
                val episodeId = episodeElement?.attr("data-id") ?: episodeElement?.attr("id")?.removePrefix("episode-")
                
                if (episodeId != null) {
                    // Now get servers for this episode
                    val serversUrl = "$mainUrl/ajax/v2/episode/servers?episodeId=$episodeId"
                    val serversResponse = app.get(serversUrl, headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to mainUrl
                    ))
                    
                    if (serversResponse.isSuccessful) {
                        val serversDoc = serversResponse.document
                        return extractAndLoadServers(serversDoc, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            // Try alternative method
        }
        
        // Alternative: Try to find episode ID directly from page
        return tryDirectEpisodeExtraction(animeId, episodeNum, subtitleCallback, callback)
    }

    private suspend fun getMovieServers(
        animeId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // For movies, the anime ID might be the movie ID
            val movieUrl = "$mainUrl/ajax/movie/episodes/$animeId"
            val response = app.get(movieUrl, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to mainUrl
            ))
            
            if (response.isSuccessful) {
                val doc = response.document
                return extractAndLoadServers(doc, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            // Try alternative
        }
        
        // Try direct MegaPlay URL
        return tryDirectMegaPlayUrl(animeId, null, subtitleCallback, callback)
    }

    private suspend fun extractAndLoadServers(
        doc: org.jsoup.nodes.Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Look for server elements with data-link-id
        val serverElements = doc.select(".server[data-link-id], [data-link-id], .ps_-block.episode-sub[data-id], .ps_-block.episode-dub[data-id]")
        
        for (server in serverElements) {
            val dataLinkId = server.attr("data-link-id").takeIf { it.isNotBlank() }
                ?: server.attr("data-id").takeIf { it.isNotBlank() }
            
            if (dataLinkId != null) {
                // Determine if this is dub or sub
                val serverText = server.text().lowercase()
                val serverClass = server.attr("class").lowercase()
                val isDub = serverText.contains("dub") || serverClass.contains("dub")
                
                // Try loading with this ID
                if (tryDirectMegaPlayUrl(dataLinkId, if (isDub) "dub" else "sub", subtitleCallback, callback)) {
                    return true
                }
            }
        }
        
        // Also look for iframe src
        val iframe = doc.selectFirst("iframe[src]")
        val iframeSrc = iframe?.attr("src")?.takeIf { it.isNotBlank() }
        
        if (iframeSrc != null && loadExtractor(iframeSrc, subtitleCallback, callback)) {
            return true
        }
        
        return false
    }

    private suspend fun tryDirectEpisodeExtraction(
        animeId: String,
        episodeNum: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try to load the anime page and find the episode directly
        val animeUrl = "$mainUrl/anime/$animeId"
        val document = app.get(animeUrl, headers = getHeaders()).document
        
        // Look for episode elements
        val episodeElement = document.select("a[href*=\"$episodeNum\"], [data-episode=\"$episodeNum\"], [data-number=\"$episodeNum\"]").first()
        val episodeLink = episodeElement?.attr("href")?.takeIf { it.isNotBlank() }
            ?: episodeElement?.attr("data-url")
        
        if (episodeLink != null) {
            // Try to load the episode page
            val epUrl = if (episodeLink.startsWith("http")) episodeLink else "$mainUrl$episodeLink"
            val epDoc = app.get(epUrl, headers = getHeaders()).document
            
            // Look for video sources
            val videoScript = epDoc.select("script:containsData(video), script:containsData(sources), script:containsData(file)").first()
            if (videoScript != null) {
                val scriptText = videoScript.html()
                
                // Try to extract MegaPlay URL
                val megaPlayPattern = Regex("""(https?://[^\s'"]*megaplay[^\s'"]*)""")
                val megaPlayMatch = megaPlayPattern.find(scriptText)
                if (megaPlayMatch != null) {
                    val megaPlayUrl = megaPlayMatch.groupValues[1]
                    return loadExtractor(megaPlayUrl, subtitleCallback, callback)
                }
                
                // Try to extract direct video URL
                val videoPattern = Regex("""(https?://[^\s'"]*\.(m3u8|mp4)[^\s'"]*)""")
                val videoMatch = videoPattern.find(scriptText)
                if (videoMatch != null) {
                    val videoUrl = videoMatch.groupValues[1]
                    callback(ExtractorLink(
                        name,
                        name,
                        videoUrl,
                        mainUrl,
                        Qualities.Unknown.value,
                        isM3u8 = videoUrl.contains(".m3u8")
                    ))
                    return true
                }
            }
        }
        
        return false
    }

    private suspend fun tryDirectMegaPlayUrl(
        id: String,
        language: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try different server patterns
        val serverPatterns = listOf("s-1", "s-2", "s-3", "s-4")
        
        for (serverNum in serverPatterns) {
            val megaPlayUrl = if (language != null) {
                "https://megaplay.buzz/stream/$serverNum/$id/$language?autostart=true"
            } else {
                "https://megaplay.buzz/stream/$serverNum/$id?autostart=true"
            }
            
            if (loadExtractor(megaPlayUrl, subtitleCallback, callback)) {
                return true
            }
        }
        
        return false
    }

    private fun extractAnimeId(document: org.jsoup.nodes.Document, url: String): String? {
        // Try multiple methods to extract anime ID
        
        // Method 1: From input field
        val inputId = document.selectFirst("input[name=id]")?.attr("value")
        if (inputId != null && inputId.isNotBlank()) return inputId
        
        // Method 2: From data attributes
        val dataId = document.selectFirst("[data-id]")?.attr("data-id")
        if (dataId != null && dataId.isNotBlank()) return dataId
        
        // Method 3: From URL
        val urlParts = url.removePrefix(mainUrl).split("/")
        for (part in urlParts) {
            if (part.isNotBlank() && !part.contains("http") && !part.contains("www") && 
                !part.matches(Regex("^[a-zA-Z-]+$"))) {
                return part
            }
        }
        
        // Method 4: Last resort - extract from URL path
        return url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() && !it.contains(".") }
    }

    private fun getEpisodeCount(document: org.jsoup.nodes.Document): Int {
        // Try multiple methods to get episode count
        
        // Method 1: From meta
        val metaEpisodes = document.selectFirst(".meta div:contains(Episodes:) + span")?.text()?.toIntOrNull() ?: 0
        if (metaEpisodes > 0) return metaEpisodes
        
        // Method 2: From dub/sub total
        val subCount = document.selectFirst(".dub-sub-total .sub")?.text()?.toIntOrNull() ?: 0
        val dubCount = document.selectFirst(".dub-sub-total .dub")?.text()?.toIntOrNull() ?: 0
        val totalCount = document.selectFirst(".dub-sub-total .total")?.text()?.toIntOrNull() ?: 0
        
        return maxOf(subCount, dubCount, totalCount, 0)
    }

    private fun getHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Cache-Control" to "max-age=0",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )
}
