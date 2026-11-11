package com.retailplatform.inventory

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Append-only inventory movement ledger, on-hand projections, adjustments, and cycle counts.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Inventory",
    allowedDependencies = ["shared", "platform", "catalog :: events", "locations :: api", "receiving :: events"],
)
internal class ModuleMetadata
