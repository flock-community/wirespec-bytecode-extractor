package com.acme.api

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.Message
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
class OrderConsumer {

    @KafkaListener(topics = ["orders.created"])
    fun onOrderCreated(event: OrderEvent) {}

    @KafkaListener(topics = ["orders.record"])
    fun onOrderRecord(record: ConsumerRecord<String, OrderEvent>) {}

    @KafkaListener(topics = ["orders.message"])
    fun onOrderMessage(message: Message<OrderEvent>) {}

    @KafkaListener(topics = ["orders.batch"])
    fun onOrderBatch(events: List<OrderEvent>) {}

    @KafkaListener(topics = ["orders.with-header"])
    fun onOrderWithHeader(@Payload event: OrderEvent, @Header("source") source: String) {}
}
