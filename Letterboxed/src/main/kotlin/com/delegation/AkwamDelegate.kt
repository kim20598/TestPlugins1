package com.letterboxed.delegation

import com.letterboxed.model.MetaItem
import com.letterboxed.util.TitleMatcher
import com.letterboxed.util.safe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AkwamDelegate : DelegateSource() {

    private val mainUrl = "https://akwam.to"

    override suspend fun searchAndLoad(
        meta: MetaItem,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val searchUrl = "$mainUrl/search?q=${meta.title}"

        val doc = safe {
            app.get(searchUrl).document
        } ?: return

        val target = TitleMatcher.normalize(meta.title)

        val resultUrl = doc.select("div.entry-box a")
            .firstOrNull {
                val title = it.text()
                TitleMatcher.normalize(title).contains(target)
            }
            ?.attr("href")
            ?: return

        loadLinksFromPage(resultUrl, callback)
    }

    private suspend fun loadLinksFromPage(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = safe {
            app.get(url).document
        } ?: return

        // سيرفرات المشاهدة
        doc.select("a.watch-btn, a.server-btn").forEach { btn ->
            val link = btn.attr("href")
            if (link.isNotBlank()) {
                loadExtractor(
                    link,
                    referer = url,
                    callback = callback
                )
            }
        }
    }
}
