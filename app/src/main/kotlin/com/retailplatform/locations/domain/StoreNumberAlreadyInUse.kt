package com.retailplatform.locations.domain

import com.retailplatform.shared.BusinessRuleViolation

class StoreNumberAlreadyInUse(
    number: StoreNumber,
) : BusinessRuleViolation("locations.store-number-in-use", "Store number $number is already in use")
