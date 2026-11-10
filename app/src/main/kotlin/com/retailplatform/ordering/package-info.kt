package com.retailplatform.ordering

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Store orders, order lines, par levels, and suggested orders.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Ordering",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
