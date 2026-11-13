package com.retailplatform.receiving

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Warehouse and plant receipts, put-away, and direct store delivery receipts.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Receiving",
    allowedDependencies = ["shared", "platform", "purchasing :: events", "locations :: api", "vendors :: api"],
)
internal class ModuleMetadata
