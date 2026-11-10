package com.retailplatform.locations

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Stores, warehouses, delivery routes, route stop schedules, and order cutoff times.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Locations",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
