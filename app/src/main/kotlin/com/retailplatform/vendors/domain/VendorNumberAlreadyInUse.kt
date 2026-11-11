package com.retailplatform.vendors.domain

import com.retailplatform.shared.BusinessRuleViolation

class VendorNumberAlreadyInUse(
    number: VendorNumber,
) : BusinessRuleViolation("vendors.vendor-number-in-use", "Vendor number $number is already in use")
