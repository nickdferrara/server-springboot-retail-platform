package com.retailplatform.ledger.adapter.`in`.events

import com.retailplatform.ledger.application.DirectDeliveryCorrectionPostingRule
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.payables.api.events.DirectDeliveryCorrectionRecorded
import com.retailplatform.platform.idempotency.ProcessedEvents
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Posts the journal entry for a correction to a store's receipt of a direct delivery. */
@Component
class DirectDeliveryCorrectionPostingListener(
    private val rule: DirectDeliveryCorrectionPostingRule,
    private val postJournalEntry: PostJournalEntry,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: DirectDeliveryCorrectionRecorded) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            rule.posting(event)?.let(postJournalEntry::handle)
        }
    }

    private companion object {
        const val MODULE = "ledger"
        const val LISTENER = "DirectDeliveryCorrectionPostingListener"
    }
}
