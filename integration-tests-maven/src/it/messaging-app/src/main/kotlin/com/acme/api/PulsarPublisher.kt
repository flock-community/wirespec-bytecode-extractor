package com.acme.api

import org.springframework.pulsar.core.PulsarTemplate
import org.springframework.stereotype.Component

@Component
class PulsarPublisher(private val pulsar: PulsarTemplate<ShipmentEvent>) {

    fun publishPulsarShipment(event: ShipmentEvent) {
        pulsar.send("shipments.created", event)
    }
}
