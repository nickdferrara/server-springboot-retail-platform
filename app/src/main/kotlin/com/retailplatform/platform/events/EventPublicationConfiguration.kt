package com.retailplatform.platform.events

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Binds the settings under `retail.events`. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventPublicationProperties::class)
class EventPublicationConfiguration
