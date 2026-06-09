package com.acme.api

import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component

@Component
class RabbitPublisher(private val rabbit: RabbitTemplate) {

    fun publishRabbitShipment(event: ShipmentEvent) {
        rabbit.convertAndSend("shipments", "shipments.created", event)
    }
}
