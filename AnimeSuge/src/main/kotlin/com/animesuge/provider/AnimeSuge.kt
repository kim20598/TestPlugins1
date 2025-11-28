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

class AnimeSuge : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "Animesuge"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA
    )

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
                
                // Look for anime items in the main page
                val animeList = doc.select("a[href*='/watch/']").mapNotNull { item ->
                    val titleElement = item.selectFirst("p.name, .name, .title, h3, h2")
                    val titleText = titleElement?.text()?.trim() ?: return@mapNotNull null
                    val href = fixUrl(item.attr("href"), mainUrl)
                    val poster = item.selectFirst("img")?.attr("src")?.let { fixUrl(it, mainUrl) }
                    
                    newAnimeSearchResponse(titleText, href) {
                        this.posterUrl = poster
                    }
                }.distinctBy { it.url }

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
        
        return doc.select("a[href*='/watch/']").mapNotNull { item ->
            val titleElement = item.selectFirst("p.name, .name, .title, h3, h2")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(item.attr("href"), mainUrl)
            val poster = item.selectFirst("img")?.attr("src")?.let { fixUrl(it, mainUrl) }
            
            newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    private fun extractEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        // Look for episode links in the range container
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Look for episode links in range divs
        val rangeEpisodes = doc.select("div.range a[href*='/ep-']").mapNotNull { episodeElement ->
            val episodeUrl = fixUrl(episodeElement.attr("href"), mainUrl)
            val episodeTitle = episodeElement.attr("title").ifBlank { 
                episodeElement.text().trim().ifBlank { "Episode ${episodeElement.text().trim()}" }
            }
            val episodeNumber = episodeElement.attr("data-slug").toIntOrNull() ?: 
                               Regex("""ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?:
                               Regex("""\d+""").find(episodeElement.text())?.value?.toIntOrNull()
            
            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.episode = episodeNumber
            }
        }
        episodes.addAll(rangeEpisodes)
        
        // Method 2: Look for any episode links
        if (episodes.isEmpty()) {
            val fallbackEpisodes = doc.select("a[href*='/ep-']").mapNotNull { episodeElement ->
                val episodeUrl = fixUrl(episodeElement.attr("href"), mainUrl)
                val episodeTitle = episodeElement.attr("title").ifBlank { 
                    episodeElement.text().trim().ifBlank { "Episode" }
                }
                val episodeNumber = Regex("""ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?:
                                   Regex("""\d+""").find(episodeElement.text())?.value?.toIntOrNull()
                
                newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.episode = episodeNumber
                }
            }
            episodes.addAll(fallbackEpisodes)
        }
        
        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Extract main metadata
        val title = doc.selectFirst("h1.title, h1, .entry-title")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("img[src*='cdn'], .poster img, [itemprop=image]")?.attr("src")?.let { fixUrl(it, mainUrl) }
        val plot = doc.selectFirst(".description, .plot, .summary")?.text()?.trim()
        
        // Extract episodes
        val episodes = extractEpisodes(doc)
        
        // Determine if it's a series or movie based on episodes
        val isMovie = episodes.isEmpty() || title.contains("movie", true) || url.contains("/movie/")
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        
        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.Anime,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
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
            val episodeDoc = app.get(episodeUrl).document
            
            var foundSources = false
            
            // Method 1: Look for iframe sources
            val iframes = episodeDoc.select("iframe[src]")
            for (iframe in iframes) {
                val iframeSrc = fixUrl(iframe.attr("src"), mainUrl)
                if (iframeSrc.isNotBlank()) {
                    loadExtractor(iframeSrc, subtitleCallback, callback)
                    foundSources = true
                }
            }
            
            // Method 2: Look for video elements
            val videoElements = episodeDoc.select("video source[src], video[src]")
            for (videoSource in videoElements) {
                val src = fixUrl(videoSource.attr("src"), mainUrl).ifBlank { 
                    fixUrl(videoSource.attr("data-src"), mainUrl) 
                }
                if (src.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "Direct Video",
                            url = src
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                    foundSources = true
                }
            }
            
            // Method 3: Look for script with video data
            val scripts = episodeDoc.select("script")
            for (script in scripts) {
                val scriptText = script.html()
                
                // Look for common video URL patterns
                val videoPatterns = listOf(
                    Regex("""(https?://[^\s"']*\.(mp4|m3u8|webm)[^\s"']*)""", RegexOption.IGNORE_CASE),
                    Regex("""file\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""src\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""video_url\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                )
                
                for (pattern in videoPatterns) {
                    val matches = pattern.findAll(scriptText)
                    for (match in matches) {
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8") || videoUrl.contains(".webm")) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "Script Video",
                                    url = videoUrl
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.type = when {
                                        videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                }
                            )
                            foundSources = true
                        }
                    }
                }
            }
            
            foundSources
            
        } catch (e: Exception) {
            false
        }
    }
}