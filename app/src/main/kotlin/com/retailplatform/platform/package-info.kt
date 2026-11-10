package com.retailplatform.platform

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Cross-cutting infrastructure: security, persistence configuration, clock, idempotency, observability,
 * and error mapping.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Platform",
    type = ApplicationModule.Type.OPEN,
    allowedDependencies = ["shared"],
)
internal class ModuleMetadata
