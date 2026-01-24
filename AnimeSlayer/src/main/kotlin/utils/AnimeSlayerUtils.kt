package com.animeslayer.utils

import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

object AnimeSlayerUtils {
    
    /**
     * Extract clean title from element
     */
    fun extractCleanTitle(element: Element): String? {
        return element.selectFirst("h2, h3, .tt, .title")?.text()?.trim()
            ?.replace(Regex("""\s+"""), " ")
            ?.replace("&#8211;", "-")
            ?.replace("&#8217;", "'")
            ?.replace("&#038;", "&")
    }
    
    /**
     * Extract clean episode number
     */
    fun extractEpisodeInfo(text: String): Pair<Int?, String?> {
        val cleaned = text.trim()
        
        // Try to extract episode number
        val epPatterns = listOf(
            Regex("""الحلقة\s+(\d+)"""),
            Regex("""Episode\s+(\d+)"""),
            Regex("""Ep\.?\s*(\d+)"""),
            Regex("""\b(\d{1,3})\b""")
        )
        
        var episodeNum: Int? = null
        for (pattern in epPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                episodeNum = match.groupValues[1].toIntOrNull()
                break
            }
        }
        
        // Extract episode title if available
        var episodeTitle: String? = null
        if (episodeNum != null) {
            val titlePart = cleaned.replace(Regex("""الحلقة\s+\d+"""), "")
                .replace(Regex("""Episode\s+\d+"""), "")
                .replace(Regex("""Ep\.?\s*\d+"""), "")
                .trim()
            if (titlePart.isNotBlank()) {
                episodeTitle = titlePart
            }
        }
        
        return Pair(episodeNum, episodeTitle)
    }
    
    /**
     * Generate SEO-friendly URL slug
     */
    fun generateSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\u0600-\\u06FF\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
    }
    
    /**
     * Fix common URL issues
     */
    fun fixCommonUrlIssues(url: String, baseUrl: String): String {
        var fixed = url.trim()
        
        // Remove query parameters that might break the URL
        fixed = fixed.substringBefore("?")
        
        // Fix protocol
        when {
            fixed.startsWith("//") -> fixed = "https:$fixed"
            fixed.startsWith("/") -> fixed = "$baseUrl$fixed"
            !fixed.startsWith("http") -> fixed = "$baseUrl/$fixed"
        }
        
        return fixed
    }
    
    /**
     * Extract quality from string
     */
    fun parseQuality(qualityStr: String?): Int {
        if (qualityStr == null) return Qualities.Unknown.value
        
        return when {
            qualityStr.contains("4k", ignoreCase = true) || 
            qualityStr.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            
            qualityStr.contains("1080", ignoreCase = true) || 
            qualityStr.contains("fullhd", ignoreCase = true) -> Qualities.P1080.value
            
            qualityStr.contains("720", ignoreCase = true) || 
            qualityStr.contains("hd", ignoreCase = true) -> Qualities.P720.value
            
            qualityStr.contains("480", ignoreCase = true) || 
            qualityStr.contains("sd", ignoreCase = true) -> Qualities.P480.value
            
            qualityStr.contains("360", ignoreCase = true) -> Qualities.P360.value
            
            qualityStr.contains("240", ignoreCase = true) -> Qualities.P240.value
            
            else -> Qualities.Unknown.value
        }
    }
}
