class AnimeSugeProvider : MainAPI() {
    override var mainUrl = "https://animesuge.bz"
    override var name = "AnimeSuge"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val lang = "en"

    // Main pages
    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest",
        "$mainUrl/popular" to "Popular",
        "$mainUrl/movies" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val animeList = document.select("div.anime-list article").map { element ->
            newAnimeSearchResponse(
                element.select("h3").text(),
                element.select("a").attr("href")
            ) {
                posterUrl = element.select("img").attr("src")
            }
        }
        return newHomePageResponse(request.name, animeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?keyword=${encode(query)}").document
        return document.select("div.anime-item").map { element ->
            newAnimeSearchResponse(
                element.select(".title").text(),
                element.select("a").attr("href")
            ) {
                posterUrl = element.select("img").attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Extract anime info
        val title = document.selectFirst("h1.title")?.text()
        val poster = document.selectFirst("img.poster")?.attr("src")
        val description = document.selectFirst(".synopsis")?.text()
        
        // Extract episodes
        val episodes = document.select(".episode-list a").map { episodeElement ->
            val epNum = episodeElement.attr("data-episode").toIntOrNull()
            val epUrl = episodeElement.attr("href")
            
            newEpisode(epUrl) {
                this.episode = epNum
                this.name = "Episode $epNum"
            }
        }.reversed() // Usually episodes are listed newest first

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Strategy 1: Look for direct video links
        val directVideo = findDirectVideo(document)
        directVideo?.let { callback(it) }
        
        // Strategy 2: Look for embedded players
        val embedUrl = document.select("iframe").attr("src")
        if (embedUrl.isNotBlank()) {
            loadExtractor(embedUrl, subtitleCallback, callback)
        }
        
        return true
    }
    
    private fun findDirectVideo(document: Document): ExtractorLink? {
        // Look for common video patterns
        val patterns = listOf(
            Regex("""file:\s*["'](.*?\.m3u8)["']"""),
            Regex("""sources:\s*\[{\s*file:\s*["'](.*?)["']"""),
            Regex("""videoUrl:\s*["'](.*?)["']""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(document.html())?.groups?.get(1)?.value?.let { url ->
                return newExtractorLink(name, name, url, ExtractorLinkType.M3U8)
            }
        }
        return null
    }
}