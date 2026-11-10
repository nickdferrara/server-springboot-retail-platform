package com.retailplatform.salestax

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Tax jurisdictions and rates, taxable and exempt sales, returns, and payments.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Sales Tax",
    allowedDependencies = ["shared", "platform"],
)
internal class ModuleMetadata
