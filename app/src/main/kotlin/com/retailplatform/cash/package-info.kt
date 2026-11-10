package com.retailplatform.cash

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Store cash, deposits, card settlement, and bank reconciliation.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Cash",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
