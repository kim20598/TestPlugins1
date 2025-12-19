package com.letterboxd.models

import kotlinx.serialization.Serializable

@Serializable
data class LetterboxdUser(
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val location: String? = null,
    val website: String? = null,
    val memberSince: String? = null,
    val stats: UserStats? = null
)

@Serializable
data class UserStats(
    val watched: Int = 0,
    val watchlist: Int = 0,
    val reviews: Int = 0,
    val lists: Int = 0,
    val likes: Int = 0,
    val followers: Int = 0,
    val following: Int = 0
)

@Serializable
data class LetterboxdFilm(
    val title: String,
    val year: Int? = null,
    val letterboxdUrl: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val rating: Float? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null
)

@Serializable
data class LetterboxdList(
    val title: String,
    val url: String,
    val description: String? = null,
    val filmCount: Int = 0,
    val likesCount: Int = 0,
    val commentsCount: Int = 0,
    val createdDate: String? = null,
    val updatedDate: String? = null,
    val films: List<LetterboxdFilm> = emptyList()
)

@Serializable
data class LetterboxdReview(
    val film: LetterboxdFilm,
    val rating: Int? = null,
    val review: String? = null,
    val date: String? = null,
    val containsSpoilers: Boolean = false,
    val likes: Int = 0,
    val comments: Int = 0
)