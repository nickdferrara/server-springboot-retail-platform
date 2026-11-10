package com.retailplatform.storesales

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Point-of-sale end-of-day file import, store sales days, and reconciliation.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Store Sales",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
