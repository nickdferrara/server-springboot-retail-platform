package com.retailplatform.platform.time

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The one source of time. Code that needs the current time takes a [Clock] in its constructor, so tests can fix it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CompanyProperties::class)
class ClockConfiguration {
    @Bean
    fun clock(company: CompanyProperties): Clock = Clock.system(company.timeZone)
}
