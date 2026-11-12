package com.retailplatform.payables

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Vendor invoices, three-way match, accruals, payable vouchers, and payments.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Payables",
    allowedDependencies = ["shared", "platform", "receiving :: events", "vendors :: events"],
)
internal class ModuleMetadata
