package com.retailplatform.vendors

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Vendors, remit-to addresses, payment terms, and vendor contacts.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Vendors",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
