package com.retailplatform.ledger.adapter.`in`.events

import com.retailplatform.ledger.application.DirectDeliveryReceivedPostingRule
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Posts the journal entry for a vendor's delivery straight to a store. */
@Component
class DirectDeliveryPostingListener(
    private val rule: DirectDeliveryReceivedPostingRule,
    private val postJournalEntry: PostJournalEntry,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: DirectDeliveryReceived) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            rule.posting(event)?.let(postJournalEntry::handle)
        }
    }

    private companion object {
        const val MODULE = "ledger"
        const val LISTENER = "DirectDeliveryPostingListener"
    }
}
