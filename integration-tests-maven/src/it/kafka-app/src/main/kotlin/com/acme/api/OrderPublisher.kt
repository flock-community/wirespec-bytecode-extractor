package com.acme.api

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class OrderPublisher(
    private val orders: KafkaTemplate<String, OrderEvent>,
    private val shipments: KafkaTemplate<String, ShipmentEvent>,
) {

    fun publishOrder(event: OrderEvent) {
        orders.send("orders.created", event)
    }

    fun publishShipment(event: ShipmentEvent) {
        shipments.send("shipments.created", event)
    }
}
