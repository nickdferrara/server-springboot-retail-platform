package com.retailplatform.pricing

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Price zones, effective-dated retail prices, promotions, and price resolution.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Pricing",
    allowedDependencies = ["shared", "platform", "locations :: events"],
)
internal class ModuleMetadata
