package com.retailplatform.pricing.adapter.`in`.scheduling

import com.retailplatform.pricing.application.AnnounceEffectivePrices
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Starts [AnnounceEffectivePrices] on a schedule, every five minutes unless configured otherwise. */
@Component
class EffectivePriceSchedule(
    private val announceEffectivePrices: AnnounceEffectivePrices,
) {
    @Scheduled(cron = "\${retail.pricing.announce-cron:0 */5 * * * *}")
    fun run() {
        do {
            val announced = announceEffectivePrices.handle()
        } while (announced == AnnounceEffectivePrices.BATCH_SIZE)
    }
}
