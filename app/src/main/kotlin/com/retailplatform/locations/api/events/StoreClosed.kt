package com.retailplatform.locations.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.StoreId
import java.time.Instant

/** A store stopped trading. [businessDate] is its last trading day. */
data class StoreClosed(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val storeId: StoreId,
    val storeNumber: String,
    val schemaVersion: Int = 1,
)
