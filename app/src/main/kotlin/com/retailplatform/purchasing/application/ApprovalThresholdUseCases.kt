package com.retailplatform.purchasing.application

import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.purchasing.domain.ApprovalThreshold
import com.retailplatform.purchasing.domain.ThresholdEffectiveInPast
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ResourceNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Use case: set the order total above which a purchase order needs a second person's approval. */
@Service
class SetApprovalThreshold(
    private val thresholds: ApprovalThresholdRepository,
    private val clock: Clock,
) {
    @RequiresRole(Roles.BUYER_APPROVER)
    @Transactional
    fun handle(threshold: ApprovalThreshold) {
        val today = BusinessDate.today(clock)
        if (threshold.effectiveFrom < today) throw ThresholdEffectiveInPast(threshold.effectiveFrom, today)
        thresholds.save(threshold)
    }
}

/** Queries: the approval threshold in effect. */
@Service
@Transactional(readOnly = true)
class ApprovalThresholdQueries(
    private val thresholds: ApprovalThresholdRepository,
    private val clock: Clock,
) {
    /** @throws ResourceNotFound if no threshold is in effect, in which case every order needs an approver. */
    fun current(): ApprovalThreshold {
        val today = BusinessDate.today(clock)
        return thresholds.findEffective(today) ?: throw ResourceNotFound("Approval threshold", "in effect on $today")
    }
}
