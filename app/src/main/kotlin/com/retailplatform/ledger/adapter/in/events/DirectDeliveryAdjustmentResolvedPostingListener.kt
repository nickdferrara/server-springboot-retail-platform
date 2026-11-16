package com.retailplatform.ledger.adapter.`in`.events

import com.retailplatform.ledger.application.DirectDeliveryAdjustmentResolvedPostingRule
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.payables.api.events.DirectDeliveryAdjustmentResolved
import com.retailplatform.platform.idempotency.ProcessedEvents
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Posts the journal entry for a direct delivery adjustment that accounting paid or wrote off. */
@Component
class DirectDeliveryAdjustmentResolvedPostingListener(
    private val rule: DirectDeliveryAdjustmentResolvedPostingRule,
    private val postJournalEntry: PostJournalEntry,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: DirectDeliveryAdjustmentResolved) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            rule.posting(event)?.let(postJournalEntry::handle)
        }
    }

    private companion object {
        const val MODULE = "ledger"
        const val LISTENER = "DirectDeliveryAdjustmentResolvedPostingListener"
    }
}
