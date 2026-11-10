package com.retailplatform.fuel

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Fuel grades, store tanks, tank readings, fuel deliveries, fuel prices, and wet-stock reconciliation.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Fuel",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
