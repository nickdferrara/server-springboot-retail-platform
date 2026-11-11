package com.retailplatform.purchasing.adapter.`in`.events

import com.retailplatform.catalog.api.events.VendorCostChanged
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.purchasing.application.RecordVendorCost
import com.retailplatform.purchasing.domain.VendorItemCost
import com.retailplatform.shared.Money
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.Currency

/** Keeps purchasing's own list of what each vendor supplies and at what cost, so orders are costed from it. */
@Component
class VendorCostListener(
    private val recordVendorCost: RecordVendorCost,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: VendorCostChanged) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            recordVendorCost.handle(
                VendorItemCost(
                    vendorId = event.vendorId,
                    itemId = event.itemId,
                    vendorSku = event.vendorSku,
                    casePack = event.casePack,
                    caseCost = Money.of(event.newCost, Currency.getInstance(event.currency)),
                ),
            )
        }
    }

    private companion object {
        const val MODULE = "purchasing"
        const val LISTENER = "VendorCostListener"
    }
}
