package com.retailplatform.posmaintenance

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Outbound item, price, promotion, and fuel price maintenance files for the point-of-sale system.
 */
@PackageInfo
@ApplicationModule(
    displayName = "POS Maintenance",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
