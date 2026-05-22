package com.acme.api

import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@KafkaListener(topics = ["shipments"])
class ShipmentRouter {

    @KafkaHandler
    fun onShipmentCreated(event: ShipmentEvent) {}

    @KafkaHandler
    fun onOrderShipped(event: OrderEvent) {}
}
