package com.catsuka.provider

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class VimeoExtractor : ExtractorApi() {
    override val name = "CatsukaVimeo"
    override val mainUrl = "https://vimeo.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast("/").substringBefore("?")
        
        try {
            val configUrl = "https://player.vimeo.com/video/$videoId/config"
            val response = app.get(configUrl, headers = mapOf(
                "Referer" to "https://www.catsuka.com/"
            ))
            
            if (response.isSuccessful) {
                val json = response.parsedSafe<Map<String, Any>>()
                val files = (json?.get("request") as? Map<String, Any>)
                    ?.get("files") as? Map<String, Any>
                
                val progressive = files?.get("progressive") as? List<Map<String, Any>>
                progressive?.forEach { video ->
                    val quality = (video["quality"] as? String) ?: "360p"
                    val videoUrl = video["url"] as? String
                    
                    if (videoUrl != null) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "Vimeo - $quality",
                                url = videoUrl,
                                referer = "https://vimeo.com/",
                                quality = Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
                
                val hls = files?.get("hls") as? Map<String, Any>
                val hlsUrl = hls?.get("url") as? String
                
                if (hlsUrl != null) {
                    M3u8Helper.generateM3u8(
                        name,
                        hlsUrl,
                        "https://vimeo.com/",
                        quality = Qualities.Unknown.value
                    ).forEach(callback)
                }
            }
        } catch (e: Exception) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "Vimeo",
                    url = "https://player.vimeo.com/video/$videoId",
                    referer = "https://vimeo.com/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        }
    }
}