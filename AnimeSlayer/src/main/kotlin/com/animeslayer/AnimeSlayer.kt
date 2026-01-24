package com.animeslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeSlayer : MainAPI() {
    override var mainUrl = "https://animeslayerweb.com"
    override var name = "AnimeSlayer"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Anime",
        "$mainUrl/anime-list/" to "All Anime",
        "$mainUrl/status/airing/" to "Currently Airing",
        "$mainUrl/status/finished/" to "Completed Anime",
        "$mainUrl/category/movies/" to "Anime Movies",
        "$mainUrl/type/tv/" to "TV Series",
        "$mainUrl/type/ova/" to "OVA",
        "$mainUrl/type/ona/" to "ONA",
        "$mainUrl/type/special/" to "Specials"
    )

    // REAL AnimeSlayer selectors
    private fun Element.toAnimeSearch(): SearchResponse? {
        // Find the anchor tag
        val anchor = this.selectFirst("a") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        // Get title from multiple possible locations
        val title = anchor.attr("title")
            .takeIf { it.isNotBlank() }
            ?: this.selectFirst("h3, h4, .title, .entry-title, .post-title")?.text()?.trim()
            ?: anchor.text().trim()
            ?: return null
        
        // Get image from multiple possible locations
        val img = this.selectFirst("img")
        val posterUrl = img?.let { 
            it.attr("src").takeIf { src -> src.isNotBlank() && !src.contains("data:image") }
                ?: it.attr("data-src")
                ?: it.attr("data-lazy-src")
        }?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val url = if (page > 1) "${request.data}page/$page/" else request.data
            val doc = app.get(url).document
            
            // REAL AnimeSlayer selectors
            val items = doc.select("article, .item, .anime-card, .post, .grid-item")
                .mapNotNull { it.toAnimeSearch() }
                .distinctBy { it.url }
            
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val doc = app.get("$mainUrl/?s=$encoded&post_type=anime").document
            
            doc.select("article, .search-result, .item")
                .mapNotNull { it.toAnimeSearch() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val doc = app.get(url).document
            
            // REAL AnimeSlayer title selector
            val title = doc.selectFirst("h1.entry-title, h1.title")?.text()?.trim()
                ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                ?: "Unknown"
            
            // REAL AnimeSlayer poster selector  
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".post-thumbnail img")?.attr("src")
                ?: doc.selectFirst(".featured-image img")?.attr("src")
            
            val plot = doc.selectFirst(".entry-content")?.text()?.trim()
                ?: doc.selectFirst("meta[name='description']")?.attr("content")
            
            // EPISODE EXTRACTION - AnimeSlayer specific
            val episodes = mutableListOf<Episode>()
            
            // Method 1: Check for episode list container
            val episodeContainer = doc.selectFirst("#EpList1, .episode-list, .episodes")
            if (episodeContainer != null) {
                val episodeLinks = episodeContainer.select("a")
                episodeLinks.forEachIndexed { index, link ->
                    val epUrl = link.attr("href").takeIf { it.isNotBlank() }
                    val epName = link.text().trim().takeIf { it.isNotBlank() } ?: "Episode ${index + 1}"
                    
                    if (epUrl != null) {
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.episode = index + 1
                            }
                        )
                    }
                }
            }
            
            // Method 2: Look for episode buttons
            if (episodes.isEmpty()) {
                val episodeButtons = doc.select(".episode-btn, .episode-button, .watch-btn")
                episodeButtons.forEachIndexed { index, btn ->
                    val epUrl = btn.attr("href").takeIf { it.isNotBlank() }
                    val epName = btn.text().trim().takeIf { it.isNotBlank() } ?: "Episode ${index + 1}"
                    
                    if (epUrl != null) {
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.episode = index + 1
                            }
                        )
                    }
                }
            }
            
            // Determine type
            if (episodes.isEmpty() || url.contains("/movie/") || doc.selectFirst(".movie-info") != null) {
                // Movie or single video
                newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } else {
                // TV Series
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
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
            if (!data.startsWith("http")) return false
            
            val doc = app.get(data).document
            var found = false
            
            // METHOD 1: Check for server buttons with data-url
            val serverButtons = doc.select("button[data-url], a[data-url], li[data-url]")
            serverButtons.forEach { btn ->
                val videoUrl = btn.attr("data-url").takeIf { it.isNotBlank() }
                if (videoUrl != null) {
                    found = true
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                }
            }
            
            // METHOD 2: Look for iframes (hidden or visible)
            val iframes = doc.select("iframe")
            iframes.forEach { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() }
                if (src != null) {
                    found = true
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
            
            // METHOD 3: Look for video sources in scripts
            if (!found) {
                val scripts = doc.select("script")
                for (script in scripts) {
                    val content = script.html()
                    
                    // Common video URL patterns
                    val patterns = listOf(
                        Regex("""(https?://[^"' ]+\.(?:m3u8|mp4|mkv))"""),
                        Regex("""src\s*[:=]\s*['"](https?://[^"']+)['"]"""),
                        Regex("""file\s*[:=]\s*['"](https?://[^"']+)['"]"""),
                        Regex("""url\s*[:=]\s*['"](https?://[^"']+)['"]""")
                    )
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(content)
                        matches.forEach { match ->
                            val url = match.groupValues[1]
                            if (url.isNotBlank()) {
                                found = true
                                loadExtractor(url, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
            
            found
        } catch (e: Exception) {
            false
        }
    }
}
