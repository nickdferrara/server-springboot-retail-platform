package com.retailplatform.pricing.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.StoreId
import java.math.BigDecimal
import java.time.Instant

/**
 * A retail price and where it applies: every store in [zoneCode], or the single store [storeId]. Exactly one of
 * the two is set.
 */
data class RetailPriceFacts(
    val itemId: ItemId,
    val zoneCode: String?,
    val storeId: StoreId?,
    val price: BigDecimal,
    val currency: String,
    val effectiveFrom: BusinessDate,
)

/** A retail price was entered to take effect on `retailPrice.effectiveFrom`. [businessDate] is when it was entered. */
data class RetailPriceScheduled(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val retailPrice: RetailPriceFacts,
    val schemaVersion: Int = 1,
)

/** A scheduled retail price is now in effect. [businessDate] is its effective date. */
data class PriceChangeEffective(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val retailPrice: RetailPriceFacts,
    val schemaVersion: Int = 1,
)
