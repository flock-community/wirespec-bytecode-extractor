package com.acme.api

import org.springframework.integration.annotation.ServiceActivator
import org.springframework.stereotype.Component

@Component
class IntegrationConsumer {

    @ServiceActivator(inputChannel = "orders.in")
    fun onIntegrationOrder(event: OrderEvent) {}
}
