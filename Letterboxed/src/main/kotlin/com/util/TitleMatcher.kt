package com.letterboxed.util

object TitleMatcher {
    fun normalize(title: String): String {
        return title.lowercase()
            .replace("[^a-z0-9]".toRegex(), "")
    }
}
