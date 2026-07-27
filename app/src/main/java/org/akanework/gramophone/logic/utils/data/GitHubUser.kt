package org.akanework.gramophone.logic.utils.data

data class GitHubUser(
    val login: String,
    val link: Boolean,
    val name: String?,
    val avatar: Int,
    val contributed: Int
)