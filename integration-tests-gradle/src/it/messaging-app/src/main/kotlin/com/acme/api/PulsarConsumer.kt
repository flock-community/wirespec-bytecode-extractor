package com.acme.api

import org.apache.pulsar.client.api.Message
import org.springframework.pulsar.annotation.PulsarListener
import org.springframework.stereotype.Component

@Component
class PulsarConsumer {

    @PulsarListener(topics = ["orders.created"])
    fun onPulsarOrderCreated(event: OrderEvent) {}

    @PulsarListener(topics = ["orders.message"])
    fun onPulsarOrderMessage(message: Message<OrderEvent>) {}
}
