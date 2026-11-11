package com.retailplatform.purchasing.application

import com.retailplatform.purchasing.domain.VendorItemCost
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Use case: remember how a vendor supplies an item and what it costs, so the item can be ordered from them. */
@Service
class RecordVendorCost(
    private val costs: VendorItemCostRepository,
) {
    @Transactional
    fun handle(cost: VendorItemCost) {
        costs.save(cost)
    }
}
