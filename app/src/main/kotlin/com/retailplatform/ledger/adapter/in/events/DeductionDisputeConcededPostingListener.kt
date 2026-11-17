package com.retailplatform.ledger.adapter.`in`.events

import com.retailplatform.ledger.application.DeductionDisputeConcededPostingRule
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.payables.api.events.DeductionDisputeConceded
import com.retailplatform.platform.idempotency.ProcessedEvents
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Posts the journal entry for a disputed deduction that accounting conceded to the vendor. */
@Component
class DeductionDisputeConcededPostingListener(
    private val rule: DeductionDisputeConcededPostingRule,
    private val postJournalEntry: PostJournalEntry,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: DeductionDisputeConceded) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            rule.posting(event)?.let(postJournalEntry::handle)
        }
    }

    private companion object {
        const val MODULE = "ledger"
        const val LISTENER = "DeductionDisputeConcededPostingListener"
    }
}
