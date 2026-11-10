package com.retailplatform.catalog

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Items, units of measure, pack sizes, item hierarchy, and vendor items.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Catalog",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
