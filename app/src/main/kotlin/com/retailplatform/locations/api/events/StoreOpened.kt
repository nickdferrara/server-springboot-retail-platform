package com.retailplatform.locations.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.StoreId
import java.time.Instant

/** A store started trading. [businessDate] is its first trading day. */
data class StoreOpened(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val storeId: StoreId,
    val storeNumber: String,
    val storeName: String,
    val timeZone: String,
    val schemaVersion: Int = 1,
)
