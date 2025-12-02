override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var foundLinks = false
    
    // First, check if the data contains a YouTube URL directly
    val youtubeId = extractYouTubeId(data)
    if (youtubeId != null) {
        val youtubeUrl = "https://www.youtube.com/watch?v=$youtubeId"
        return extractYouTubeStream(youtubeUrl, subtitleCallback, callback)
    }
    
    // Try to get the actual page content
    try {
        val doc = app.get(data, referer = mainUrl).document
        
        // Method 1: Direct YouTube iframe extraction
        doc.select("iframe[src*='youtube.com'], iframe[src*='youtu.be']").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val ytId = extractYouTubeId(src)
                if (ytId != null) {
                    val ytUrl = "https://www.youtube.com/watch?v=$ytId"
                    if (extractYouTubeStream(ytUrl, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                }
            }
        }
        
        // Method 2: Look for embedded YouTube URLs in scripts
        doc.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Look for YouTube video IDs
            val patterns = listOf(
                Regex("""youtube\.com/embed/([a-zA-Z0-9_-]{11})"""),
                Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
                Regex("""['"]videoId['"]\s*:\s*['"]([a-zA-Z0-9_-]{11})['"]"""),
                Regex("""watch\?v=([a-zA-Z0-9_-]{11})""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val videoId = match.groupValues[1]
                    if (videoId.length == 11) { // YouTube IDs are 11 characters
                        val ytUrl = "https://www.youtube.com/watch?v=$videoId"
                        if (extractYouTubeStream(ytUrl, subtitleCallback, callback)) {
                            foundLinks = true
                        }
                    }
                }
            }
        }
        
        // Method 3: Look for other streaming iframes (alkoora.live, stream-in.live)
        if (!foundLinks) {
            doc.select("iframe[src*='alkoora.live'], iframe[src*='stream-in.live']").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }
        
        // Method 4: Look for direct video sources
        if (!foundLinks) {
            doc.select("video source[src], video[src]").forEach { video ->
                val src = video.attr("src")
                if (src.isNotBlank() && (src.contains(".m3u8") || src.contains(".mp4"))) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name - Direct Stream",
                            src,
                            if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            }
        }
        
        // Method 5: Look for streaming links in anchors
        if (!foundLinks) {
            doc.select("a[href*='stream'], a[href*='watch'], a[href*='youtube'], a[href*='youtu.be']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank()) {
                    val ytId = extractYouTubeId(href)
                    if (ytId != null) {
                        val ytUrl = "https://www.youtube.com/watch?v=$ytId"
                        if (extractYouTubeStream(ytUrl, subtitleCallback, callback)) {
                            foundLinks = true
                        }
                    } else {
                        loadExtractor(href, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
        }
        
    } catch (e: Exception) {
        // If all else fails, try to extract from the URL directly
        try {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            foundLinks = true
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    return foundLinks
}

// Helper function to extract YouTube video ID more reliably
private fun extractYouTubeId(url: String): String? {
    val patterns = listOf(
        Regex("""youtube\.com/embed/([a-zA-Z0-9_-]{11})"""),
        Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
        Regex("""youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})"""),
        Regex("""youtube\.com/v/([a-zA-Z0-9_-]{11})"""),
        Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})"""),
        Regex("""videoId=([a-zA-Z0-9_-]{11})"""),
        Regex("""v/([a-zA-Z0-9_-]{11})""")
    )
    
    for (pattern in patterns) {
        val match = pattern.find(url)
        if (match != null && match.groupValues.size > 1) {
            return match.groupValues[1]
        }
    }
    
    // Also check if the URL itself is just a YouTube ID
    if (url.matches(Regex("""[a-zA-Z0-9_-]{11}"""))) {
        return url
    }
    
    return null
}

// Enhanced YouTube stream extraction
private suspend fun extractYouTubeStream(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    try {
        // Clean the URL first
        val cleanUrl = if (url.startsWith("http")) url else "https://www.youtube.com/watch?v=$url"
        
        // Use CloudStream's YouTube extractor
        loadExtractor(cleanUrl, mainUrl, subtitleCallback, callback)
        return true
    } catch (e: Exception) {
        // Try alternative method - construct direct YouTube URL
        val videoId = extractYouTubeId(url)
        if (videoId != null) {
            try {
                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                loadExtractor(youtubeUrl, mainUrl, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                // Last resort: try with different YouTube extractor
                try {
                    loadExtractor("https://youtube.com/watch?v=$videoId", mainUrl, subtitleCallback, callback)
                    return true
                } catch (e: Exception) {
                    return false
                }
            }
        }
    }
    return false
}
