package com.catsuka.provider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class VimeoExtractor : ExtractorApi() {
    override val name = "CatsukaVimeo"
    override val mainUrl = "https://vimeo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast("/").substringBefore("?")
        
        try {
            // First try to extract from player page
            val playerUrl = "https://player.vimeo.com/video/$videoId"
            val response = app.get(playerUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (response.isSuccessful) {
                val html = response.text
                
                // Look for HLS URL pattern in the JSON
                val hlsPattern = Regex(""""url":"(https://[^"]+\\.m3u8[^"]*)"""")
                val hlsMatch = hlsPattern.find(html)
                
                if (hlsMatch != null) {
                    var hlsUrl = hlsMatch.groupValues[1]
                    hlsUrl = hlsUrl.replace("\\/", "/")  // Fix JSON escaping
                    
                    // Generate M3U8 links
                    M3u8Helper.generateM3u8(
                        name,
                        hlsUrl,
                        "https://vimeo.com/"
                    ).forEach(callback)
                    return
                }
                
                // Look for progressive MP4 URLs
                val mp4Pattern = Regex(""""url":"(https://[^"]+\\.mp4[^"]*)"""")
                val mp4Matches = mp4Pattern.findAll(html).toList()
                
                if (mp4Matches.isNotEmpty()) {
                    for (match in mp4Matches) {
                        var mp4Url = match.groupValues[1]
                        mp4Url = mp4Url.replace("\\/", "/")
                        
                        callback(
                            ExtractorLink(
                                name,
                                "Vimeo Video",
                                mp4Url,
                                "https://vimeo.com/",
                                Qualities.P1080.value
                            )
                        )
                    }
                    return
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Fallback to config API
        try {
            val configUrl = "https://player.vimeo.com/video/$videoId/config"
            val configResponse = app.get(configUrl, headers = mapOf(
                "Referer" to (referer ?: "https://www.catsuka.com/"),
                "Origin" to "https://player.vimeo.com",
                "Accept" to "application/json",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            if (configResponse.isSuccessful) {
                val text = configResponse.text
                val json = tryParseJson<Map<String, Any>>(text)
                
                // Try to extract HLS URL
                val request = json?.get("request") as? Map<String, Any>
                val files = request?.get("files") as? Map<String, Any>
                val hls = files?.get("hls") as? Map<String, Any>
                val hlsUrl = hls?.get("url") as? String
                
                if (hlsUrl != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        hlsUrl,
                        "https://vimeo.com/"
                    ).forEach(callback)
                    return
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Final fallback
        callback(
            ExtractorLink(
                name,
                "Vimeo Embed",
                "https://player.vimeo.com/video/$videoId",
                "https://vimeo.com/"
            )
        )
    }
}
