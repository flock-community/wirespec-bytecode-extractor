package com.acme.api

import org.springframework.jms.core.JmsTemplate
import org.springframework.stereotype.Component

@Component
class JmsPublisher(private val jms: JmsTemplate) {

    fun publishJmsOrder(event: OrderEvent) {
        jms.convertAndSend("orders.created", event)
    }
}
