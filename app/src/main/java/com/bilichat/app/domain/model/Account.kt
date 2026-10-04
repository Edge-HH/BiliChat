package com.bilichat.app.domain.model

data class Account(
    val mid: Long,
    val name: String,
    val avatarUrl: String?,
)

data class UserCard(
    val mid: Long,
    val name: String,
    val avatarUrl: String?,
)
