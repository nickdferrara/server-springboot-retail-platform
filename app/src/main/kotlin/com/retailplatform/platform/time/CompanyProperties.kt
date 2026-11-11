package com.retailplatform.platform.time

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId
import java.time.ZoneOffset

/** Company-wide settings under `retail.company`. */
@ConfigurationProperties("retail.company")
data class CompanyProperties(
    /** The time zone in which business dates, cutoffs, and fiscal periods are resolved. */
    val timeZone: ZoneId = ZoneOffset.UTC,
)
