package com.retailplatform.payables.adapter.`in`.metrics

import com.retailplatform.payables.application.OpenItemCount
import com.retailplatform.payables.application.OpenItemKind
import com.retailplatform.payables.application.OpenItemQueries
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Counts the direct delivery items waiting for accounting, every five minutes unless configured otherwise, so an
 * alert rule can remind accounting of ones left open too long.
 *
 * Publishes two gauges, each tagged with `kind` (`adjustment`, `late-bill`, `dispute`, or `notice`):
 * - `retail.payables.open-items.open`: items still waiting;
 * - `retail.payables.open-items.overdue`: those of them that have waited longer than their kind allows.
 */
@Component
class OpenItemMonitor(
    private val queries: OpenItemQueries,
    meters: MeterRegistry,
) {
    private val latest = AtomicReference(emptyMap<OpenItemKind, OpenItemCount>())

    init {
        OpenItemKind.entries.forEach { kind ->
            val tag = kind.name.lowercase().replace('_', '-')
            Gauge
                .builder("retail.payables.open-items.open") { latest.get()[kind]?.open ?: 0 }
                .description("Direct delivery items waiting for accounting")
                .tag("kind", tag)
                .register(meters)
            Gauge
                .builder("retail.payables.open-items.overdue") { latest.get()[kind]?.overdue ?: 0 }
                .description("Direct delivery items that have waited for accounting longer than allowed")
                .tag("kind", tag)
                .register(meters)
        }
    }

    @Scheduled(
        initialDelayString = "\${retail.payables.ageing.monitor-interval:5m}",
        fixedDelayString = "\${retail.payables.ageing.monitor-interval:5m}",
    )
    fun run() {
        check()
    }

    /** Counts the open items, updates the gauges, and returns what it found. */
    fun check(): Map<OpenItemKind, OpenItemCount> = queries.counts().also(latest::set)
}
