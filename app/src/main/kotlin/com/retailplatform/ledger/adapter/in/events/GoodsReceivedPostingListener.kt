package com.retailplatform.ledger.adapter.`in`.events

import com.retailplatform.ledger.application.GoodsReceivedPostingRule
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.platform.idempotency.ProcessedEvents
import com.retailplatform.receiving.api.events.GoodsReceived
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** Posts the journal entry for goods received at a warehouse. */
@Component
class GoodsReceivedPostingListener(
    private val rule: GoodsReceivedPostingRule,
    private val postJournalEntry: PostJournalEntry,
    private val processedEvents: ProcessedEvents,
) {
    @ApplicationModuleListener
    fun on(event: GoodsReceived) {
        processedEvents.once(MODULE, LISTENER, event.eventId) {
            rule.posting(event)?.let(postJournalEntry::handle)
        }
    }

    private companion object {
        const val MODULE = "ledger"
        const val LISTENER = "GoodsReceivedPostingListener"
    }
}
