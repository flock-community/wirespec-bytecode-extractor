package com.acme.api

import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

@Component
class RabbitConsumer {

    @RabbitListener(queues = ["orders.created"])
    fun onRabbitOrderCreated(event: OrderEvent) {}

    @RabbitListener(queues = ["orders.message"])
    fun onRabbitOrderMessage(message: Message<OrderEvent>) {}
}
