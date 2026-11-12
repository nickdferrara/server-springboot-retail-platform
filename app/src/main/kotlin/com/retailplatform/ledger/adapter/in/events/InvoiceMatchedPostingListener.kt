package com.retailplatform.ledger.adapter.`in`.events

import com.retailplatform.ledger.application.InvoiceMatchedPostingRule
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.payables.api.events.InvoiceMatched
import com.retailplatform.platform.idempotency.ProcessedEvents
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Posts the journal entry for a vendor invoice that matched its purchase order and receipts. */
@Component
class InvoiceMatchedPostingListener(
    private val rule: InvoiceMatchedPostingRule,
    private val postJournalEntry: PostJournalEntry,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: InvoiceMatched) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            rule.posting(event)?.let(postJournalEntry::handle)
        }
    }

    private companion object {
        const val MODULE = "ledger"
        const val LISTENER = "InvoiceMatchedPostingListener"
    }
}
