package com.letterboxed.metadata

import com.letterboxed.model.*
import com.lagradost.cloudstream3.TvType

// =========================
// TMDB RAW MODELS
// =========================
data class TmdbSearchResponse(
    val results: List<TmdbSearchItem>
)

data class TmdbSearchItem(
    val id: Int,
    val media_type: String?,
    val title: String?,
    val name: String?,
    val original_title: String?,
    val original_name: String?,
    val poster_path: String?,
    val overview: String?,
    val release_date: String?,
    val first_air_date: String?
)

data class TmdbMovieDetails(
    val id: Int,
    val title: String,
    val original_title: String?,
    val poster_path: String?,
    val overview: String?,
    val release_date: String?
)

data class TmdbTvDetails(
    val id: Int,
    val name: String,
    val original_name: String?,
    val poster_path: String?,
    val overview: String?,
    val first_air_date: String?
)

// =========================
// MAPPER
// =========================
object TmdbMapper {

    private const val IMG = "https://image.tmdb.org/t/p/w500"

    fun mapSearch(item: TmdbSearchItem): MetaItem? {
        val type = when (item.media_type) {
            "movie" -> TvType.Movie
            "tv" -> TvType.TvSeries
            else -> return null
        }

        val title = item.title ?: item.name ?: return null
        val original = item.original_title ?: item.original_name
        val year = extractYear(item.release_date ?: item.first_air_date)

        return MetaItem(
            id = "${item.media_type}:${item.id}",
            title = title,
            originalTitle = original,
            poster = item.poster_path?.let { IMG + it },
            plot = item.overview,
            year = year,
            type = type
        )
    }

    fun mapMovie(movie: TmdbMovieDetails): MetaItem {
        return MetaItem(
            id = "movie:${movie.id}",
            title = movie.title,
            originalTitle = movie.original_title,
            poster = movie.poster_path?.let { IMG + it },
            plot = movie.overview,
            year = extractYear(movie.release_date),
            type = TvType.Movie
        )
    }

    fun mapTv(tv: TmdbTvDetails): MetaItem {
        return MetaItem(
            id = "tv:${tv.id}",
            title = tv.name,
            originalTitle = tv.original_name,
            poster = tv.poster_path?.let { IMG + it },
            plot = tv.overview,
            year = extractYear(tv.first_air_date),
            type = TvType.TvSeries
        )
    }

    private fun extractYear(date: String?): Int? {
        return date?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
    }
}
