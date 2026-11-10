package com.retailplatform.shared

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Shared kernel: immutable value types such as Money, Quantity, typed IDs, and BusinessDate.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Shared Kernel",
    allowedDependencies = ["shared"],
)
internal class ModuleMetadata
