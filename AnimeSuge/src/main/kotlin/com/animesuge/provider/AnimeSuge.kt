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
                
                // Correct selectors based on actual homepage structure
                val animeList = doc.select(".anime.mini-card .item, .anime.main-card .item, a[href*='/watch/']").mapNotNull { item ->
                    val titleElement = item.selectFirst(".name, p.name, .detail .name, .item-bottom .name")
                    val titleText = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    // Get href - handle both relative and absolute URLs
                    val href = when {
                        item.hasAttr("abs:href") -> item.attr("abs:href")
                        item.hasAttr("href") -> {
                            val relativeHref = item.attr("href")
                            if (relativeHref.startsWith("http")) relativeHref else "$mainUrl$relativeHref"
                        }
                        else -> return@mapNotNull null
                    }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    
                    val poster = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: 
                                item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                    
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

        // If no categories work, try the homepage directly
        if (items.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/home").document
                
                // Try to get anime from the "Recently Updated" section
                val updatedList = doc.select(".original.anime.main-card .item").mapNotNull { item ->
                    val titleElement = item.selectFirst(".name, .item-bottom .name a")
                    val titleText = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = item.selectFirst("a[href*='/watch/']")?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    
                    val poster = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: 
                                item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                    
                    newAnimeSearchResponse(titleText, href) {
                        this.posterUrl = poster
                    }
                }
                
                if (updatedList.isNotEmpty()) {
                    items.add(HomePageList("Recently Updated", updatedList))
                }
                
                // Try to get from "Recently Added" section
                val addedList = doc.select(".hot-stat .added .anime.mini-card .item").mapNotNull { item ->
                    val titleElement = item.selectFirst(".name, .detail .name")
                    val titleText = titleElement?.text()?.trim() ?: return@mapNotNull null
                    
                    val href = item.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    
                    val poster = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: 
                                item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                    
                    newAnimeSearchResponse(titleText, href) {
                        this.posterUrl = poster
                    }
                }
                
                if (addedList.isNotEmpty()) {
                    items.add(HomePageList("Recently Added", addedList))
                }
                
            } catch (e: Exception) {
                // If everything fails, throw error
            }
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/filter?keyword=$encodedQuery"
        val doc = app.get(searchUrl).document
        
        return doc.select(".anime.mini-card .item, .anime.main-card .item, a[href*='/watch/']").mapNotNull { item ->
            val titleElement = item.selectFirst(".name, p.name, .detail .name, .item-bottom .name")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            
            val href = when {
                item.hasAttr("abs:href") -> item.attr("abs:href")
                item.hasAttr("href") -> {
                    val relativeHref = item.attr("href")
                    if (relativeHref.startsWith("http")) relativeHref else "$mainUrl$relativeHref"
                }
                else -> return@mapNotNull null
            }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            
            val poster = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: 
                        item.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            
            newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    private fun extractEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        // Try multiple selectors for episode list
        val episodeElements = doc.select("#media-episode .range a[href*='/watch/'], .range a[href*='/watch/'], [id*='episode'] a[href*='/watch/']")
        
        if (episodeElements.isEmpty()) {
            // If no episodes found, check if it's actually a movie by looking for movie indicators
            val isMovie = doc.select(".meta div:contains(Type) + span").any { it.text().contains("movie", true) } ||
                         doc.select("h1, .title").any { it.text().contains("movie", true) } ||
                         doc.outerHtml().contains("/movie/")
            
            if (isMovie) {
                return emptyList() // It's a movie, return empty episodes
            }
            
            // If not a movie but no episodes found, try to find at least one episode link
            val singleEpisode = doc.select("a[href*='/watch/'][href*='/ep-']").firstOrNull()
            if (singleEpisode != null) {
                val episodeUrl = singleEpisode.attr("abs:href")
                val episodeNumber = Regex("""ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                return listOf(
                    newEpisode(episodeUrl) {
                        name = "Episode 1"
                        this.episode = episodeNumber
                    }
                )
            }
        }
        
        return episodeElements.mapNotNull { episodeElement ->
            val episodeUrl = episodeElement.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            
            // Extract episode number from multiple possible sources
            val episodeNumber = episodeElement.attr("data-slug").toIntOrNull() ?: 
                               episodeElement.text().trim().toIntOrNull() ?: 
                               Regex("""ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?:
                               Regex("""/ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
            
            val episodeTitle = episodeElement.attr("title").ifBlank { 
                episodeElement.attr("data-num").ifBlank {
                    episodeElement.text().trim().ifBlank {
                        "Episode ${episodeNumber ?: "Unknown"}"
                    }
                }
            }
            
            newEpisode(episodeUrl) {
                name = episodeTitle
                this.episode = episodeNumber
            }
        }.sortedBy { it.episode }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // Extract main metadata from the actual HTML structure
        val title = doc.selectFirst("h1.title, .title, h1, [itemprop=name]")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("#media-info .poster img, [itemprop=image]")?.attr("src")
        val plot = doc.selectFirst(".description, .plot, [itemprop=description]")?.text()?.trim()
        
        // Extract additional metadata to help determine type
        val type = doc.selectFirst(".meta div:contains(Type) + span")?.text()?.trim()
        val totalEpisodes = doc.selectFirst(".meta div:contains(Episodes) + span")?.text()?.toIntOrNull()
        
        // Extract episodes
        val episodes = extractEpisodes(doc)
        
        // Better logic to determine if it's a movie or series
        val isMovie = when {
            // Explicit movie indicators
            type?.contains("movie", true) == true -> true
            url.contains("/movie/") -> true
            doc.select("h1, .title").any { it.text().contains("movie", true) } -> true
            
            // Series indicators
            episodes.size > 1 -> false
            totalEpisodes != null && totalEpisodes > 1 -> false
            doc.select(".meta div:contains(Episodes) + span").isNotEmpty() -> false
            doc.select("#media-episode").isNotEmpty() -> false
            
            // Default to series if we found at least one episode
            episodes.isNotEmpty() -> false
            
            // Otherwise assume it's a movie
            else -> true
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
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
                val iframeSrc = iframe.attr("abs:src")
                if (iframeSrc.isNotBlank()) {
                    foundSources = loadExtractor(iframeSrc, subtitleCallback, callback) || foundSources
                }
            }
            
            // Method 2: Look for video elements with data
            val videoElements = episodeDoc.select("video source[src], video[src]")
            for (videoSource in videoElements) {
                val src = videoSource.attr("abs:src").ifBlank { 
                    videoSource.attr("data-src").ifBlank {
                        videoSource.attr("src")
                    }
                }
                if (src.isNotBlank() && (src.contains(".mp4") || src.contains(".m3u8") || src.contains(".webm"))) {
                    callback(
                        newExtractorLink(
                            name = "Direct Video",
                            url = src,
                            source = this.name,
                            type = when {
                                src.contains(".m3u8") -> ExtractorLinkType.M3U8
                                else -> ExtractorLinkType.VIDEO
                            }
                        ) {
                            this.quality = getQualityFromName(src) ?: Qualities.Unknown.value
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
                                    name = "Script Video",
                                    url = videoUrl,
                                    source = this.name,
                                    type = when {
                                        videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                ) {
                                    this.quality = getQualityFromName(videoUrl) ?: Qualities.Unknown.value
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