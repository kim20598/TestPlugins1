package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Catsuka : MainAPI() {
    override var mainUrl = "https://www.catsuka.com"
    override var name = "Catsuka Player"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.OVA
    )

    // ALL CATEGORIES in main page - everything visible at once!
    override val mainPage = mainPageOf(
        // Main navigation
        "$mainUrl/player/highlights/" to "🎬 Highlights",
        "$mainUrl/player/updates/" to "🆕 Latest Updates",
        
        // Video Categories
        "$mainUrl/player/categorie/courtmetrage" to "🎥 Short Films",
        "$mainUrl/player/categorie/pilote" to "✈️ Pilots",
        "$mainUrl/player/categorie/episode" to "📺 Episodes",
        "$mainUrl/player/categorie/catsukatrailers" to "🎞️ Movie Trailers",
        "$mainUrl/player/categorie/clip" to "🎵 Music Videos",
        "$mainUrl/player/categorie/pub" to "📢 Commercials",
        "$mainUrl/player/categorie/cinematique" to "🎮 Cinematics",
        "$mainUrl/player/categorie/nanars" to "🗑️ Junk/Weird",
        "$mainUrl/player/categorie/opening" to "🎭 Openings",
        "$mainUrl/player/categorie/trailer" to "🚀 Trailers",
        "$mainUrl/player/categorie/extrait" to "🎬 Excerpts",
        "$mainUrl/player/categorie/demoreel" to "🎨 Demoreels",
        "$mainUrl/player/categorie/sakuga" to "✨ Sakuga",
        "$mainUrl/player/categorie/makingof" to "🎬 Making Of",
        "$mainUrl/player/categorie/parodies" to "👑 Tributes",
        "$mainUrl/player/categorie/autres" to "📦 Others",
        "$mainUrl/player/categorie/catsukanolife" to "📡 Catsuka TV",
        
        // Binge Categories
        "$mainUrl/player/binge/category-animepost2000_watchable-nofilter_sort-rank/" to "🇯🇵 Anime 2000s",
        "$mainUrl/player/binge/category-anime1990_watchable-nofilter_sort-rank/" to "🇯🇵 Anime 1990s",
        "$mainUrl/player/binge/category-anime1980_watchable-nofilter_sort-rank/" to "🇯🇵 Anime 1980s",
        "$mainUrl/player/binge/category-animeretro_watchable-nofilter_sort-rank/" to "🇯🇵 Anime Retro",
        "$mainUrl/player/binge/category-usa2000_watchable-nofilter_sort-rank/" to "🇺🇸 US 2000s",
        "$mainUrl/player/binge/category-usa8090_watchable-nofilter_sort-rank/" to "🇺🇸 US 1980-1990",
        "$mainUrl/player/binge/category-usaretro_watchable-nofilter_sort-rank/" to "🇺🇸 US Retro",
        "$mainUrl/player/binge/category-fr2000_watchable-nofilter_sort-rank/" to "🇫🇷 FR 2000s",
        "$mainUrl/player/binge/category-fr8090_watchable-nofilter_sort-rank/" to "🇫🇷 FR 1980-1990",
        "$mainUrl/player/binge/category-frretro_watchable-nofilter_sort-rank/" to "🇫🇷 FR Retro",
        "$mainUrl/player/binge/category-other_watchable-nofilter_sort-rank/" to "🌍 Other Regions",
        "$mainUrl/player/binge/category-nostalgiafr_watchable-nofilter_sort-rank/" to "🇫🇷 Nostalgia (France)",
        "$mainUrl/player/binge/category-nostalgiaus_watchable-nofilter_sort-rank/" to "🇺🇸 Nostalgia (USA)",
        "$mainUrl/player/binge/category-young_watchable-nofilter_sort-rank/" to "👶 Young Audiences",
        "$mainUrl/player/binge/category-movieova_watchable-nofilter_sort-rank/" to "🎬 Movies & OVA",
        "$mainUrl/player/binge/category-indiesshort_watchable-nofilter_sort-rank/" to "🎨 Indies & Shorts",
        "$mainUrl/player/binge/category-all_watchable-nofilter_sort-rank/" to "📚 ALL TITLES"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url).document
        
        val items = parseVideoItems(doc)
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun parseVideoItems(doc: Element): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Multiple selector strategies for different page layouts
        val selectors = listOf(
            ".swiper-slide a",
            "a.movie",
            ".video-item a",
            ".zonetableau li a",
            "article a",
            "a[href*='/player/']:has(img)"
        )
        
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                elements.forEach { element ->
                    val href = fixUrl(element.attr("href"))
                    // Filter out category pages and only get actual video pages
                    if (href.contains("/player/") && 
                        !href.contains("/categorie/") && 
                        !href.contains("/binge/") &&
                        !href.contains("/highlights/") &&
                        !href.contains("/updates/") &&
                        !href.contains("/categories/")) {
                        
                        val title = element.selectFirst("img")?.attr("alt") 
                            ?: element.selectFirst("span")?.text()
                            ?: element.attr("title")
                            ?: element.text().trim()
                        
                        if (title.isNotBlank() && title != "embed") {
                            val poster = fixUrlNull(
                                element.selectFirst("img")?.attr("src") 
                                    ?: element.selectFirst("img")?.attr("data-src")
                                    ?: element.selectFirst("img")?.attr("data-lazy")
                            )
                            
                            items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                                this.posterUrl = poster
                            })
                        }
                    }
                }
                break // Use the first selector that works
            }
        }
        
        return items.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/player/?recherche=$encodedQuery"
        val doc = app.get(searchUrl).document
        
        return parseVideoItems(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1, .post__name, .entry-title, .txtblanc25")?.text()?.trim() 
            ?: "Unknown Title"
        
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[itemprop='image']")?.attr("content")
                ?: doc.selectFirst("img.lazy")?.attr("data-src")
                ?: doc.selectFirst(".poster img")?.attr("src")
                ?: doc.selectFirst("img")?.attr("src")
        )
        
        val description = doc.selectFirst(".description, .plot, .synopsis, .post__story, .videosinfos_left")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
        
        // Extract director and production
        var director: String? = null
        var production: String? = null
        var dateAdded: String? = null
        
        doc.select("span.txtorange17, b:contains(Director), b:contains(Production)").forEach { element ->
            val text = element.text()
            val parentText = element.parent()?.text() ?: ""
            
            when {
                text.contains("Director", ignoreCase = true) || parentText.contains("Director", ignoreCase = true) -> {
                    director = parentText.substringAfter("Director").substringBefore("Production").trim()
                }
                text.contains("Production", ignoreCase = true) || parentText.contains("Production", ignoreCase = true) -> {
                    production = parentText.substringAfter("Production").trim()
                }
            }
        }
        
        // Extract date
        doc.select("span.txtblanc14, span.txtblanc12").forEach { span ->
            val text = span.text()
            if (text.contains("added on", ignoreCase = true) || text.contains("Video added", ignoreCase = true)) {
                dateAdded = text
            }
        }
        
        val tags = doc.select("a[href*='/tag/'], a[href*='/player/tag/']").map { it.text().trim() }
        
        val plotBuilder = StringBuilder()
        if (director != null && director.isNotBlank()) plotBuilder.append("🎬 Director: $director\n")
        if (production != null && production.isNotBlank()) plotBuilder.append("🏢 Production: $production\n")
        if (dateAdded != null) plotBuilder.append("📅 $dateAdded\n")
        if (description != null) plotBuilder.append("\n$description")
        
        // Check for episodes/playlist
        val episodes = mutableListOf<Episode>()
        
        // Method 1: Universal video player playlist
        doc.select(".universal_video_player_list li").forEachIndexed { index, li ->
            val videoId = li.attr("data-vimeo") ?: li.attr("data-youtube")
            val epTitle = li.attr("data-title") ?: "Episode ${index + 1}"
            
            if (videoId != null) {
                episodes.add(newEpisode(videoId) {
                    this.name = epTitle
                    this.episode = index + 1
                })
            }
        }
        
        // Method 2: Video links in the page
        if (episodes.isEmpty()) {
            doc.select("a[href*='/player/']").forEachIndexed { index, a ->
                val href = fixUrl(a.attr("href"))
                val epTitle = a.text().trim()
                if (href != url && href.contains("/player/") && 
                    !href.contains("?") && 
                    epTitle.isNotBlank() &&
                    !epTitle.contains("See all")) {
                    episodes.add(newEpisode(href) {
                        this.name = if (epTitle.length > 3) epTitle else "Episode ${index + 1}"
                        this.episode = index + 1
                    })
                }
            }
        }
        
        if (episodes.size > 1) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plotBuilder.toString().trim()
                this.tags = tags
            }
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plotBuilder.toString().trim()
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            // Vimeo ID
            data.matches(Regex("\\d+")) -> {
                val vimeoUrl = "https://player.vimeo.com/video/$data"
                loadExtractor(vimeoUrl, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            // YouTube ID
            data.matches(Regex("[A-Za-z0-9_-]{11}")) -> {
                val youtubeUrl = "https://www.youtube.com/watch?v=$data"
                loadExtractor(youtubeUrl, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            // Direct URL
            data.startsWith("http") -> {
                loadExtractor(data, "$mainUrl/player/", subtitleCallback, callback)
                true
            }
            else -> {
                try {
                    val doc = app.get(data).document
                    
                    // Look for iframes
                    val iframe = doc.selectFirst("iframe[src*='vimeo'], iframe[src*='youtube']")
                    if (iframe != null) {
                        val src = fixUrl(iframe.attr("src"))
                        return loadExtractor(src, data, subtitleCallback, callback)
                    }
                    
                    // Look in scripts
                    val scripts = doc.select("script")
                    for (script in scripts) {
                        val content = script.html()
                        
                        // Vimeo patterns
                        val vimeoPatterns = listOf(
                            Regex("vimeo\\.com/(\\d+)"),
                            Regex("vimeo\\.com/video/(\\d+)"),
                            Regex("player\\.vimeo\\.com/video/(\\d+)")
                        )
                        
                        for (pattern in vimeoPatterns) {
                            val match = pattern.find(content)
                            if (match != null) {
                                val vimeoId = match.groupValues[1]
                                val vimeoUrl = "https://player.vimeo.com/video/$vimeoId"
                                return loadExtractor(vimeoUrl, data, subtitleCallback, callback)
                            }
                        }
                        
                        // YouTube patterns
                        val youtubePatterns = listOf(
                            Regex("youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})"),
                            Regex("youtube\\.com/embed/([A-Za-z0-9_-]{11})"),
                            Regex("youtu\\.be/([A-Za-z0-9_-]{11})")
                        )
                        
                        for (pattern in youtubePatterns) {
                            val match = pattern.find(content)
                            if (match != null) {
                                val youtubeId = match.groupValues[1]
                                val youtubeUrl = "https://www.youtube.com/watch?v=$youtubeId"
                                return loadExtractor(youtubeUrl, data, subtitleCallback, callback)
                            }
                        }
                    }
                    
                    false
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}
