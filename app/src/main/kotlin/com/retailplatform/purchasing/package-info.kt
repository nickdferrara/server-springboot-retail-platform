package com.retailplatform.purchasing

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Purchase orders, approval policies, vendor delivery schedules, and lead times.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Purchasing",
    allowedDependencies = ["shared", "platform", "catalog :: events", "vendors :: api", "locations :: api"],
)
internal class ModuleMetadata
