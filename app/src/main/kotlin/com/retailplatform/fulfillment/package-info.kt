package com.retailplatform.fulfillment

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Pick waves, pick tasks, loads, shipments, and proof of delivery.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Fulfillment",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
