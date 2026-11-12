package com.retailplatform.ledger.api.events

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.JournalEntryId
import java.math.BigDecimal
import java.time.Instant

/**
 * A balanced journal entry was posted to the general ledger. [amount] is the total of its debits, which equals
 * the total of its credits. [sourceEventId] is the business event the entry was posted for, if it had one.
 */
data class JournalEntryPosted(
    val eventId: EventId,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val journalEntryId: JournalEntryId,
    val journalEntryNumber: String,
    val journal: String,
    val fiscalYear: Int,
    val fiscalPeriod: Int,
    val sourceModule: String,
    val sourceEventId: EventId?,
    val amount: BigDecimal,
    val currency: String,
    val schemaVersion: Int = 1,
)
