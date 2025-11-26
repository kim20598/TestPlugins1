package com.animesuge.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "AnimeSuge"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Recently Updated",
        "$mainUrl/new-release" to "Recently Added", 
        "$mainUrl/most-viewed" to "Most Viewed",
        "$mainUrl/status/finished-airing" to "Completed",
        "$mainUrl/status/currently-airing" to "Ongoing",
        "$mainUrl/status/not-yet-aired" to "Upcoming",
        "$mainUrl/type/tv" to "TV Series",
        "$mainUrl/type/movie" to "Movies",
        "$mainUrl/type/ova" to "OVA",
        "$mainUrl/type/ona" to "ONA",
        "$mainUrl/type/special" to "Special",
        "$mainUrl/type/music" to "Music",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/adventure" to "Adventure",
        "$mainUrl/genre/cars" to "Cars",
        "$mainUrl/genre/comedy" to "Comedy",
        "$mainUrl/genre/dementia" to "Dementia",
        "$mainUrl/genre/demons" to "Demons",
        "$mainUrl/genre/drama" to "Drama",
        "$mainUrl/genre/ecchi" to "Ecchi",
        "$mainUrl/genre/fantasy" to "Fantasy",
        "$mainUrl/genre/game" to "Game",
        "$mainUrl/genre/harem" to "Harem",
        "$mainUrl/genre/historical" to "Historical",
        "$mainUrl/genre/horror" to "Horror",
        "$mainUrl/genre/isekai" to "Isekai",
        "$mainUrl/genre/josei" to "Josei",
        "$mainUrl/genre/kids" to "Kids",
        "$mainUrl/genre/magic" to "Magic",
        "$mainUrl/genre/martial-arts" to "Martial Arts",
        "$mainUrl/genre/mecha" to "Mecha",
        "$mainUrl/genre/military" to "Military",
        "$mainUrl/genre/music" to "Music",
        "$mainUrl/genre/mystery" to "Mystery",
        "$mainUrl/genre/parody" to "Parody",
        "$mainUrl/genre/police" to "Police",
        "$mainUrl/genre/psychological" to "Psychological",
        "$mainUrl/genre/romance" to "Romance",
        "$mainUrl/genre/samurai" to "Samurai",
        "$mainUrl/genre/school" to "School",
        "$mainUrl/genre/sci-fi" to "Sci-Fi",
        "$mainUrl/genre/seinen" to "Seinen",
        "$mainUrl/genre/shoujo" to "Shoujo",
        "$mainUrl/genre/shoujo-ai" to "Shoujo Ai",
        "$mainUrl/genre/shounen" to "Shounen",
        "$mainUrl/genre/shounen-ai" to "Shounen Ai",
        "$mainUrl/genre/slice-of-life" to "Slice of Life",
        "$mainUrl/genre/space" to "Space",
        "$mainUrl/genre/sports" to "Sports",
        "$mainUrl/genre/super-power" to "Super Power",
        "$mainUrl/genre/supernatural" to "Supernatural",
        "$mainUrl/genre/thriller" to "Thriller",
        "$mainUrl/genre/unknown" to "Unknown",
        "$mainUrl/genre/vampire" to "Vampire"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document

        val home = document.select("div.item, a.item, .item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(request.name, home),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".name, p.name, .detail .name")?.text()?.trim() ?: return null
        
        val href = when {
            hasAttr("href") -> attr("href")
            else -> selectFirst("a")?.attr("href")
        } ?: return null
        
        val fullUrl = fixUrl(href)
        
        val posterUrl = fixUrlNull(
            selectFirst("img")?.attr("src") ?:
            selectFirst("img")?.attr("data-src") ?:
            selectFirst(".poster img")?.attr("src")
        )
        
        // Determine type from URL or class - improved detection
        val typeText = selectFirst(".type, .item-status .type")?.text()?.trim()?.lowercase() ?: ""
        val type = when {
            fullUrl.contains("/movie/") || typeText.contains("movie") -> TvType.AnimeMovie
            fullUrl.contains("/ova/") || typeText.contains("ova") -> TvType.OVA
            fullUrl.contains("/ona/") || typeText.contains("ona") -> TvType.OVA
            fullUrl.contains("/special/") || typeText.contains("special") -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, fullUrl, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/filter?keyword=$encodedQuery").document

        return document.select("div.item, a.item, .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Improved episode page detection
        val isEpisodePage = url.contains("/ep-") || document.selectFirst("#player, .watch-wrap") != null

        if (isEpisodePage) {
            return loadEpisodePage(document, url)
        } else {
            return loadSeriesPage(document, url)
        }
    }

    private suspend fun loadEpisodePage(document: org.jsoup.nodes.Document, url: String): LoadResponse? {
        val title = document.selectFirst("h1.title, h1, .title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".poster img, [itemprop=image]")?.attr("src"))
        val description = document.selectFirst(".description, .story, .synopsis, .cts-wrapper, [itemprop=description]")?.text()?.trim()
        
        // Extract episode number from URL
        val episodeNumber = Regex("""/ep-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        return newMovieLoadResponse(title, url, TvType.Anime, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun loadSeriesPage(document: org.jsoup.nodes.Document, url: String): LoadResponse? {
        val title = document.selectFirst("h1.title, h1, .title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".poster img, [itemprop=image]")?.attr("src"))
        val description = document.selectFirst(".description, .story, .synopsis, .cts-wrapper, [itemprop=description]")?.text()?.trim()
        
        // Extract metadata with improved selectors
        val statusText = document.selectFirst(".meta span:contains(Status), .meta div:contains(Status)")?.nextElementSibling()?.text()?.trim()
        val yearText = document.selectFirst(".meta span:contains(Premiered), .meta div:contains(Premiered)")?.nextElementSibling()?.text()
        val year = yearText?.substringAfterLast(" ")?.toIntOrNull()
        val genres = document.select(".meta span:contains(Genre) a, .meta div:contains(Genre) a").map { it.text().trim() }
        val typeText = document.selectFirst(".meta span:contains(Type), .meta div:contains(Type)")?.nextElementSibling()?.text()?.trim() ?: "TV"
        
        val type = when (typeText.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova" -> TvType.OVA
            "ona" -> TvType.OVA
            "special" -> TvType.OVA
            else -> TvType.Anime
        }

        // Extract episodes from API
        val episodes = extractEpisodesFromApi(document, url)

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        }
    }

    private suspend fun extractEpisodesFromApi(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Get series ID from data attribute - improved extraction
        val seriesId = document.selectFirst("main[data-id], [data-id]")?.attr("data-id") ?: 
                      Regex("""data-id=["']?(\d+)""").find(document.html())?.groupValues?.get(1)
        
        if (seriesId != null) {
            try {
                // Fetch episodes via API
                val episodesResponse = app.get("$mainUrl/api/seasons/$seriesId").text
                if (episodesResponse.isNotBlank() && episodesResponse != "null") {
                    // Parse the HTML content from API response
                    val doc = Jsoup.parse(episodesResponse)
                    doc.select("a[href*='/ep-']").forEach { episodeLink ->
                        val episodeUrl = fixUrl(episodeLink.attr("href"))
                        val episodeText = episodeLink.text().trim()
                        val episodeNumber = extractEpisodeNumber(episodeUrl, episodeText)
                        
                        episodes.add(newEpisode(episodeUrl) {
                            this.name = episodeText.ifBlank { "Episode $episodeNumber" }
                            this.episode = episodeNumber
                        })
                    }
                }
            } catch (e: Exception) {
                // Fallback to page extraction
                episodes.addAll(extractEpisodesFromPage(document, baseUrl))
            }
        } else {
            episodes.addAll(extractEpisodesFromPage(document, baseUrl))
        }
        
        return episodes.distinctBy { it.episode }.sortedBy { it.episode }
    }

    private fun extractEpisodesFromPage(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        document.select("a[href*='/ep-']").forEach { episodeLink ->
            val episodeUrl = fixUrl(episodeLink.attr("href"))
            val episodeText = episodeLink.text().trim()
            val episodeNumber = extractEpisodeNumber(episodeUrl, episodeText)
            
            episodes.add(newEpisode(episodeUrl) {
                this.name = episodeText.ifBlank { "Episode $episodeNumber" }
                this.episode = episodeNumber
            })
        }
        
        return episodes
    }

    private fun extractEpisodeNumber(url: String, text: String): Int {
        // Try to extract from URL first
        Regex("""/ep-(\d+)""").find(url)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        
        // Try to extract from text - improved regex
        Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        
        Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        
        Regex("""(\d+)""").find(text)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        
        return 0
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        var foundLinks = false

        // Method 1: Extract from media-servers via API - improved detection
        document.select("#media-servers, [id*='media'], [class*='media']").forEach { mediaServers ->
            mediaServers.select("[data-id]").forEach { serverElement ->
                val serverId = serverElement.attr("data-id")
                if (serverId.isNotBlank()) {
                    try {
                        val serverUrl = "$mainUrl/ajax/server/$serverId"
                        val response = app.get(serverUrl, referer = data).text
                        
                        // Extract video URLs from server response
                        val videoUrls = extractAllVideoUrls(response)
                        videoUrls.forEach { videoUrl ->
                            if (videoUrl.isNotBlank()) {
                                foundLinks = true
                                loadExtractor(videoUrl, data, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Method 2: Look for direct video URLs in scripts
        if (!foundLinks) {
            document.select("script").forEach { script ->
                val scriptContent = script.html() + script.attr("src")
                val videoUrls = extractAllVideoUrls(scriptContent)
                videoUrls.forEach { videoUrl ->
                    if (videoUrl.isNotBlank()) {
                        foundLinks = true
                        loadExtractor(videoUrl, data, subtitleCallback, callback)
                    }
                }
            }
        }

        // Method 3: Look for iframe sources - improved detection
        if (!foundLinks) {
            document.select("iframe, [src*='megaplay'], [src*='stream']").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && (src.contains("megaplay") || src.contains("stream"))) {
                    foundLinks = true
                    loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                }
            }
        }

        return foundLinks
    }

    private fun extractAllVideoUrls(html: String): List<String> {
        val urls = mutableListOf<String>()

        // Look for MegaPlay iframe - improved regex
        Regex("""<iframe[^>]*src=["']?([^"'>]*(?:megaplay|stream)[^"'>]*)""", RegexOption.IGNORE_CASE).findAll(html).forEach {
            urls.add(it.groupValues[1])
        }
        
        // Look for direct video URLs
        Regex("""(https?://[^\s"']*(?:megaplay|stream)[^\s"']*)""", RegexOption.IGNORE_CASE).findAll(html).forEach {
            urls.add(it.value)
        }
        
        // Look for base64 encoded URLs - improved detection
        Regex("""stream/s-1/([^"'\s]+)""").findAll(html).forEach {
            val encoded = it.groupValues[1]
            urls.add("https://megaplay.buzz/stream/s-1/$encoded")
        }
        
        // Look for generic video URLs - improved file extensions
        Regex("""(https?://[^\s"']*\.(?:mp4|m3u8|mkv|avi|webm|flv)[^\s"']*)""", RegexOption.IGNORE_CASE).findAll(html).forEach {
            urls.add(it.value)
        }

        return urls.distinct()
    }
}
