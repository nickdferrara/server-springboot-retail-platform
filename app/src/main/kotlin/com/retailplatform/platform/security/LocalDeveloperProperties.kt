package com.retailplatform.platform.security

import org.springframework.boot.context.properties.ConfigurationProperties

/** Who every request runs as under the `local` profile, set under `retail.security.local`. */
@ConfigurationProperties("retail.security.local")
data class LocalDeveloperProperties(
    val user: String = "local-developer",
    val roles: List<String> = emptyList(),
)
