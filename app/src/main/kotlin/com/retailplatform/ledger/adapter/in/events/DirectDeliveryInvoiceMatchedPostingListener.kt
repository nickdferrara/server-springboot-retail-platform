package com.retailplatform.ledger.adapter.`in`.events

import com.retailplatform.ledger.application.DirectDeliveryInvoiceMatchedPostingRule
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatched
import com.retailplatform.platform.idempotency.ProcessedEvents
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Posts the journal entry for a direct store delivery invoice that matched its ticket. */
@Component
class DirectDeliveryInvoiceMatchedPostingListener(
    private val rule: DirectDeliveryInvoiceMatchedPostingRule,
    private val postJournalEntry: PostJournalEntry,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: DirectDeliveryInvoiceMatched) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            rule.posting(event)?.let(postJournalEntry::handle)
        }
    }

    private companion object {
        const val MODULE = "ledger"
        const val LISTENER = "DirectDeliveryInvoiceMatchedPostingListener"
    }
}
