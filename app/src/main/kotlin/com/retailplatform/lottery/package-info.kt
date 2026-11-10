package com.retailplatform.lottery

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Instant ticket packs, lotto sales, validations, weekly settlement, and commissions.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Lottery",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
