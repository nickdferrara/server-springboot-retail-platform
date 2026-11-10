package com.retailplatform.quality

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Quality specifications, test results, lot quality status, control point logs, and recalls.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Quality",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
