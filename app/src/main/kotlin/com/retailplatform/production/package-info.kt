package com.retailplatform.production

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Plants, production lines, recipes, production orders, batches, and lots.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Production",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
