package com.acme.api

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class OrderConsumer {

    @KafkaListener(topics = ["orders.created"])
    fun onOrderCreated(event: OrderEvent) {
        // no-op: integration fixture
    }
}
