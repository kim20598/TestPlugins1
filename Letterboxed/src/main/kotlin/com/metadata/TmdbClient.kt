package com.letterboxed.metadata

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.letterboxed.model.*

class TmdbClient {

    companion object {
        private const val TMDB_API_KEY = "e5bb3f8f15d40063752ff87b4c360b05"
        private const val TMDB_URL = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p/w500"
    }

    // =========================
    // SEARCH (Movie + TV)
    // =========================
    suspend fun search(query: String): List<MetaItem> {
        val res = app.get(
            "$TMDB_URL/search/multi",
            params = mapOf(
                "api_key" to TMDB_API_KEY,
                "query" to query,
                "language" to "en-US"
            )
        ).parsed<TmdbSearchResponse>()

        return res.results.mapNotNull {
            TmdbMapper.mapSearch(it)
        }
    }

    // =========================
    // TRENDING
    // =========================
    suspend fun getTrending(): List<MetaItem> {
        val res = app.get(
            "$TMDB_URL/trending/all/week",
            params = mapOf(
                "api_key" to TMDB_API_KEY
            )
        ).parsed<TmdbSearchResponse>()

        return res.results.mapNotNull {
            TmdbMapper.mapSearch(it)
        }
    }

    // =========================
    // LOAD DETAILS
    // =========================
    suspend fun load(id: String): MetaItem {
        val (type, realId) = id.split(":")

        val url = if (type == "tv") {
            "$TMDB_URL/tv/$realId"
        } else {
            "$TMDB_URL/movie/$realId"
        }

        val res = app.get(
            url,
            params = mapOf(
                "api_key" to TMDB_API_KEY,
                "language" to "en-US"
            )
        )

        return if (type == "tv") {
            TmdbMapper.mapTv(res.parsed())
        } else {
            TmdbMapper.mapMovie(res.parsed())
        }
    }
}
