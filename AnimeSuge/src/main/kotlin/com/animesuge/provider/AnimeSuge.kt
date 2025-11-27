package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element
import java.util.Base64

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "Animesuge"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA
    )

    private fun getPoster(element: Element?): String? {
        return element?.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = listOf(
            "$mainUrl/latest-updated" to "Recently Updated",
            "$mainUrl/new-release" to "New Releases", 
            "$mainUrl/most-viewed" to "Popular Anime",
            "$mainUrl/status/finished-airing" to "Completed",
            "$mainUrl/status/currently-airing" to "Ongoing",
            "$mainUrl/status/not-yet-aired" to "Upcoming"
        )

        val items = mutableListOf<HomePageList>()
        
        for ((url, title) in categories) {
            try {
                val fullUrl = if (page > 1) "$url?page=$page" else url
                val doc = app.get(fullUrl).document
                
                val animeList = doc.select(".anime-item, .mini-card .item, .anime.mini-card .item").mapNotNull { item ->
                    val titleElement = item.selectFirst(".name, .title, p.name")
                    val titleText = titleElement?.text()?.trim() ?: return@mapNotNull null
                    val href = item.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val poster = getPoster(item) ?: item.selectFirst("img")?.attr("src")
                    
                    newAnimeSearchResponse(titleText, href) {
                        this.posterUrl = poster
                    }
                }

                if (animeList.isNotEmpty()) {
                    items.add(HomePageList(title, animeList))
                }
            } catch (e: Exception) {
                // Continue with next category if one fails
            }
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/filter?keyword=$encodedQuery"
        val doc = app.get(searchUrl).document
        
        return doc.select(".anime-item, .mini-card .item, .anime.mini-card .item").mapNotNull { item ->
            val titleElement = item.selectFirst(".name, .title, p.name")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val href = item.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = getPoster(item) ?: item.selectFirst("img")?.attr("src")
            
            newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
    }

    private fun extractYearFromPremiered(premiered: String?): Int? {
        premiered ?: return null
        val yearMatch = Regex("""(\d{4})""").find(premiered)
        return yearMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        return doc.select("div.range a[href*='/watch/']").mapNotNull { episodeElement ->
            val episodeUrl = episodeElement.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val episodeTitle = episodeElement.attr("title").ifBlank { episodeElement.text().trim() }
            val episodeNumber = episodeElement.attr("data-slug").toIntOrNull() ?: 
                               Regex("""\d+""").find(episodeElement.text())?.value?.toIntOrNull()
            
            newEpisode(episodeUrl) {
                name = episodeTitle
                this.episode = episodeNumber
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Extract main metadata
        val title = doc.selectFirst("h1.title")?.text()?.trim() ?: "Unknown"
        val japaneseTitle = doc.selectFirst("h1.title")?.attr("data-jp")
        val poster = doc.selectFirst(".poster img, [itemprop=image]")?.attr("src")
        val plot = doc.selectFirst(".description .short div, .description .full div")?.text()?.trim()
        
        // Extract detailed metadata
        val type = doc.select(".meta div:contains(Type) span").firstOrNull()?.text()?.trim()
        val status = doc.select(".meta div:contains(Status) span").firstOrNull()?.text()?.trim()
        val premiered = doc.select(".meta div:contains(Premiered) span").firstOrNull()?.text()?.trim()
        val totalEpisodes = doc.select(".meta div:contains(Episodes) span").firstOrNull()?.text()?.toIntOrNull()
        val duration = doc.select(".meta div:contains(Duration) span").firstOrNull()?.text()?.trim()
        
        // Extract genres
        val genres = doc.select(".meta div:contains(Genre) a").map { it.text().trim() }
        
        // Determine content type
        val animeType = when {
            type?.contains("Movie", true) == true -> TvType.AnimeMovie
            type?.contains("OVA", true) == true -> TvType.OVA
            type?.contains("Special", true) == true -> TvType.OVA
            else -> TvType.Anime
        }
        
        // Extract episodes
        val episodes = extractEpisodes(doc)
        
        val year = extractYearFromPremiered(premiered)
        
        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = animeType,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            
            // Add recommendations from side panel
            this.recommendations = doc.select(".side-panel .anime.mini-card .item").mapNotNull { recItem ->
                val recTitle = recItem.selectFirst("p.name")?.text()?.trim() ?: return@mapNotNull null
                val recHref = recItem.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val recPoster = recItem.selectFirst("img")?.attr("src")
                
                newAnimeSearchResponse(recTitle, recHref) {
                    this.posterUrl = recPoster
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data
        
        return try {
            // Step 1: Load episode page
            val episodeDoc = app.get(episodeUrl).document
            
            // Step 2: Extract encrypted data-ids from episode links
            val episodeLink = episodeDoc.selectFirst("a[data-ids]")
            val encryptedIds = episodeLink?.attr("data-ids")
            
            if (encryptedIds != null) {
                // Step 3: Try to decode the encrypted data
                try {
                    val decodedData = String(Base64.getDecoder().decode(encryptedIds))
                    // The decoded data might contain server information or direct links
                    // This would require additional processing based on Animesuge's API
                } catch (e: Exception) {
                    // Continue with alternative methods if decoding fails
                }
            }
            
            // Step 4: Look for direct video sources in the page
            val videoScripts = episodeDoc.select("script").map { it.html() }
            
            // Pattern 1: Look for direct video URLs in scripts
            val videoUrlPatterns = listOf(
                Regex("""(https?://[^\s"']*\.(mp4|m3u8|webm)[^\s"']*)""", RegexOption.IGNORE_CASE),
                Regex("""file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""src\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            )
            
            for (script in videoScripts) {
                for (pattern in videoUrlPatterns) {
                    val matches = pattern.findAll(script)
                    for (match in matches) {
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8") || videoUrl.contains(".webm")) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "Direct Video",
                                    url = videoUrl
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.type = when {
                                        videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                }
                            )
                        }
                    }
                }
            }
            
            // Pattern 2: Look for iframe sources
            val iframes = episodeDoc.select("iframe[src]")
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("abs:src")
                if (iframeSrc.isNotBlank()) {
                    // Load extractor for iframe sources
                    loadExtractor(iframeSrc, subtitleCallback, callback)
                }
            }
            
            // Pattern 3: Look for video elements
            val videoElements = episodeDoc.select("video source[src]")
            for (videoSource in videoElements) {
                val src = videoSource.attr("abs:src")
                val quality = videoSource.attr("size").ifBlank { videoSource.attr("label") }
                
                if (src.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "Video Source",
                            url = src
                        ) {
                            this.quality = getQualityFromName(quality)
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                }
            }
            
            // Step 5: If no direct sources found, try to extract from media servers
            val serverElements = episodeDoc.select("[data-server], .server-item, .media-server")
            for (serverElement in serverElements) {
                val serverData = serverElement.attr("data-server")
                val serverName = serverElement.text().trim()
                
                if (serverData.isNotBlank()) {
                    // This would typically require an API call to get video sources
                    // For now, we'll simulate this by trying to construct video URLs
                    try {
                        val serverUrl = "$mainUrl/ajax/server/$serverData"
                        val serverResponse = app.get(serverUrl)
                        
                        // Parse server response for video URLs
                        val serverDoc = Jsoup.parse(serverResponse.text)
                        val serverVideos = serverDoc.select("source[src], [data-video-src]")
                        
                        for (video in serverVideos) {
                            val videoUrl = video.attr("abs:src").ifBlank { video.attr("data-video-src") }
                            if (videoUrl.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "$serverName - ${video.attr("size").ifBlank { video.attr("label") }}",
                                        url = videoUrl
                                    ) {
                                        this.quality = getQualityFromName(video.attr("size").ifBlank { video.attr("label") })
                                        this.type = when {
                                            videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                                            else -> ExtractorLinkType.VIDEO
                                        }
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Continue with next server if one fails
                    }
                }
            }
            
            // Return true if we found any video sources
            iframes.isNotEmpty() || videoElements.isNotEmpty() || serverElements.isNotEmpty()
            
        } catch (e: Exception) {
            false
        }
    }
}