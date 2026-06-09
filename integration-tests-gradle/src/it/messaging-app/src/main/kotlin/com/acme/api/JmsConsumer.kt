package com.acme.api

import org.springframework.jms.annotation.JmsListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

@Component
class JmsConsumer {

    @JmsListener(destination = "orders.created")
    fun onJmsOrderCreated(event: OrderEvent) {}

    @JmsListener(destination = "orders.with-header")
    fun onJmsOrderWithHeader(@Payload event: OrderEvent, @Header("source") source: String) {}
}
