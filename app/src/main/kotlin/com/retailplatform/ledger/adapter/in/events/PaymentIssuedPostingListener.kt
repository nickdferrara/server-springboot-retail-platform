package com.retailplatform.ledger.adapter.`in`.events

import com.retailplatform.ledger.application.PaymentIssuedPostingRule
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.payables.api.events.PaymentIssued
import com.retailplatform.platform.idempotency.ProcessedEvents
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Posts the journal entry for a payment issued to a vendor. */
@Component
class PaymentIssuedPostingListener(
    private val rule: PaymentIssuedPostingRule,
    private val postJournalEntry: PostJournalEntry,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: PaymentIssued) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            rule.posting(event)?.let(postJournalEntry::handle)
        }
    }

    private companion object {
        const val MODULE = "ledger"
        const val LISTENER = "PaymentIssuedPostingListener"
    }
}
