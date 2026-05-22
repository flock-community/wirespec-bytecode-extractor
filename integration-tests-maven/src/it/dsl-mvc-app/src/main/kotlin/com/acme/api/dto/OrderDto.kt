package com.acme.api.dto

data class OrderDto(
    val id: String,
    val sku: String,
    val quantity: Int,
)
