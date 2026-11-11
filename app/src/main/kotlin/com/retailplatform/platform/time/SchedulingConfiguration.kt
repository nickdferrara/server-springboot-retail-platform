package com.retailplatform.platform.time

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** Turns on `@Scheduled` methods, which inbound scheduling adapters use to start recurring use cases. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration
