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
                
                // Correct selectors based on actual HTML structure
                val animeList = doc.select(".anime.mini-card .item, a[href^='/watch/']").mapNotNull { item ->
                    val titleElement = item.selectFirst("p.name, .name, .title, h1, h2, h3")
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
                    
                    val poster = item.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                    
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
        
        return doc.select(".anime.mini-card .item, a[href^='/watch/']").mapNotNull { item ->
            val titleElement = item.selectFirst("p.name, .name, .title, h1, h2, h3")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            
            val href = when {
                item.hasAttr("abs:href") -> item.attr("abs:href")
                item.hasAttr("href") -> {
                    val relativeHref = item.attr("href")
                    if (relativeHref.startsWith("http")) relativeHref else "$mainUrl$relativeHref"
                }
                else -> return@mapNotNull null
            }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            
            val poster = item.selectFirst("img")?.attr("src")
            
            newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    private fun extractEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        return doc.select("#media-episode .range a[href*='/watch/']").mapNotNull { episodeElement ->
            val episodeUrl = episodeElement.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            
            // Extract episode number from data-slug attribute or text
            val episodeNumber = episodeElement.attr("data-slug").toIntOrNull() ?: 
                               episodeElement.text().trim().toIntOrNull() ?: 
                               Regex("""ep-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
            
            val episodeTitle = episodeElement.attr("title").ifBlank { 
                episodeElement.attr("data-num").ifBlank {
                    "Episode ${episodeNumber ?: episodeElement.text().trim()}"
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
        
        // Extract episodes
        val episodes = extractEpisodes(doc)
        
        // Extract additional metadata
        val type = doc.selectFirst(".meta div:contains(Type) + span")?.text()?.trim()
        val status = doc.selectFirst(".meta div:contains(Status) + span")?.text()?.trim()
        val totalEpisodes = doc.selectFirst(".meta div:contains(Episodes) + span")?.text()?.toIntOrNull()
        
        // Determine if it's a series or movie
        val isMovie = type.equals("movie", true) || 
                     url.contains("/movie/") || 
                     (episodes.isEmpty() && totalEpisodes == null)
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = null // Extract from metadata if available
            }
        }
        
        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.Anime,
            episodes = episodes,
            posterUrl = poster,
            plot = plot,
            year = null, // Extract from "Premiered" metadata if needed
            status = when (status?.lowercase()) {
                "currently airing" -> ShowStatus.Ongoing
                "finished airing" -> ShowStatus.Completed
                "not yet aired" -> ShowStatus.ComingSoon
                else -> null
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Boolean
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
                            source = this.name,
                            name = "Direct Video",
                            url = src,
                            referer = "$mainUrl/",
                            quality = getQualityFromName(src) ?: Qualities.Unknown.value,
                            type = when {
                                src.contains(".m3u8") -> ExtractorLinkType.M3U8
                                else -> ExtractorLinkType.VIDEO
                            }
                        )
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
                                    url = videoUrl,
                                    referer = "$mainUrl/",
                                    quality = getQualityFromName(videoUrl) ?: Qualities.Unknown.value,
                                    type = when {
                                        videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                )
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