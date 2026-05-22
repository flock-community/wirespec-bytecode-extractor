package com.acme.api.dto

data class ItemDto(
    val id: String,
    val name: String,
    val price: Double,
    val tags: List<String>,
)
