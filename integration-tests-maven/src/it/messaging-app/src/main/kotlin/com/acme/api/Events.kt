package com.acme.api

data class OrderEvent(
    val id: String,
    val customerId: String,
)

data class ShipmentEvent(
    val id: String,
    val carrier: String,
)
