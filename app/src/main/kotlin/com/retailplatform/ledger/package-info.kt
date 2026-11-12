package com.retailplatform.ledger

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Chart of accounts, fiscal periods, journals, journal entries, and posting rules.
 */
@PackageInfo
@ApplicationModule(
    displayName = "Ledger",
    allowedDependencies = ["shared", "platform", "receiving :: events"],
)
internal class ModuleMetadata
