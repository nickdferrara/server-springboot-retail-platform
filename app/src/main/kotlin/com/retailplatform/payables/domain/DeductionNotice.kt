package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.TypedId
import com.retailplatform.shared.TypedIdFactory
import java.time.Clock
import java.util.UUID

/** Identifies a deduction notice. */
@JvmInline
value class DeductionNoticeId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<DeductionNoticeId>(::DeductionNoticeId)
}

/** The number payables gives a deduction notice, which the vendor quotes back: one to ten digits. */
@JvmInline
value class DeductionNoticeNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is one to ten digits. */
        fun of(value: String): DeductionNoticeNumber {
            require(FORMAT.matches(value)) { "Not a deduction notice number: '$value'" }
            return DeductionNoticeNumber(value)
        }
    }
}

/**
 * One item a deduction notice holds back: the vendor billed [unitsBilled] at [unitCost] and the store received
 * [unitsReceived], fewer, so the difference is not paid.
 */
@ConsistentCopyVisibility
data class DeductionLine private constructor(
    val itemId: ItemId,
    val unitsBilled: Int,
    val unitsReceived: Int,
    val unitCost: Money,
) {
    /** The units billed that the store did not receive. */
    val unitsDeducted: Int get() = unitsBilled - unitsReceived

    /** What is held back for the item, at the unit cost billed. */
    val amount: Money get() = unitCost * unitsDeducted

    companion object {
        /** @throws IllegalArgumentException unless fewer units were received than billed, and none negative. */
        fun of(
            itemId: ItemId,
            unitsBilled: Int,
            unitsReceived: Int,
            unitCost: Money,
        ): DeductionLine {
            require(unitsReceived in 0..<unitsBilled) { "A deduction is for units billed and not received" }
            return DeductionLine(itemId, unitsBilled, unitsReceived, unitCost)
        }
    }
}

/** How a notice reaches a vendor. */
enum class NoticeChannel { EMAIL, POST, IN_PERSON, OTHER }

/** That a notice was sent: on [sentOn], by [channel], to [recipient], as [recordedBy] recorded. */
@ConsistentCopyVisibility
data class NoticeSending private constructor(
    val sentOn: BusinessDate,
    val channel: NoticeChannel,
    val recipient: String,
    val recordedBy: String,
) {
    companion object {
        const val MAX_RECIPIENT_LENGTH = 100

        /** @throws IllegalArgumentException if [recipient] is blank or longer than [MAX_RECIPIENT_LENGTH] trimmed. */
        fun of(
            sentOn: BusinessDate,
            channel: NoticeChannel,
            recipient: String,
            recordedBy: String,
        ): NoticeSending {
            val trimmed = recipient.trim()
            require(trimmed.isNotEmpty()) { "A notice is sent to someone" }
            require(trimmed.length <= MAX_RECIPIENT_LENGTH) {
                "A recipient has at most $MAX_RECIPIENT_LENGTH characters"
            }
            return NoticeSending(sentOn, channel, trimmed, recordedBy)
        }
    }
}

/**
 * A sending that was recorded in error and taken back on [withdrawnOn]; [decision] says who withdrew it and why.
 * It stays on the notice so that what the vendor was told, and when, can be traced.
 */
data class WithdrawnSending(
    val sending: NoticeSending,
    val withdrawnOn: BusinessDate,
    val decision: MatchOverride,
)

/** The short-paid invoice a deduction notice is about. */
data class DeductedInvoice(
    val invoiceId: InvoiceId,
    val number: VendorInvoiceNumber,
    val invoiceDate: BusinessDate,
    val ticket: BilledTicket,
)

/**
 * Tells a vendor what was held back from one of its direct delivery invoices and why: the items and units it
 * billed that the store did not receive. It is raised when the invoice is paid short. Accounting sends it by its
 * own means and records that it did; [sending] is `null` until then. A sending recorded in error is withdrawn,
 * which makes the notice unsent again, and kept in [withdrawn], the earliest first.
 */
data class DeductionNotice(
    val id: DeductionNoticeId,
    val number: DeductionNoticeNumber,
    val invoice: DeductedInvoice,
    val raisedOn: BusinessDate,
    val reason: String,
    val lines: List<DeductionLine>,
    val sending: NoticeSending? = null,
    val version: Int = 0,
    val withdrawn: List<WithdrawnSending> = emptyList(),
) {
    /** What the notice holds back in all. */
    val amount: Money get() = lines.map { it.amount }.reduce(Money::plus)

    /**
     * The notice recorded as sent.
     *
     * @throws DeductionNoticeAlreadySent if it was recorded as sent before.
     * @throws DeductionNoticeSentDateInvalid if it was sent before it was raised, or after today.
     */
    fun sent(
        sending: NoticeSending,
        clock: Clock,
    ): DeductionNotice {
        if (this.sending != null) throw DeductionNoticeAlreadySent(number)
        if (sending.sentOn < raisedOn || sending.sentOn > BusinessDate.today(clock)) {
            throw DeductionNoticeSentDateInvalid(number)
        }
        return copy(sending = sending)
    }

    /**
     * The notice with its recorded sending taken back, on the word of [decision]: it is unsent again and waits
     * for the right sending to be recorded.
     *
     * @throws DeductionNoticeNotSent if no sending is recorded.
     */
    fun withdrawSending(
        decision: MatchOverride,
        clock: Clock,
    ): DeductionNotice {
        val recorded = sending ?: throw DeductionNoticeNotSent(number)
        val taken = WithdrawnSending(recorded, BusinessDate.today(clock), decision)
        return copy(sending = null, withdrawn = withdrawn + taken)
    }

    companion object {
        /**
         * Raises the notice for [invoice], paid short on the word of [approval], holding back [lines].
         *
         * @throws IllegalArgumentException if nothing is held back.
         */
        fun raise(
            number: DeductionNoticeNumber,
            invoice: DeductedInvoice,
            approval: MatchOverride,
            lines: List<DeductionLine>,
            clock: Clock,
        ): DeductionNotice {
            require(lines.isNotEmpty()) { "A deduction notice holds something back" }
            return DeductionNotice(
                DeductionNoticeId.generate(clock),
                number,
                invoice,
                BusinessDate.today(clock),
                approval.reason,
                lines,
            )
        }
    }
}

class DeductionNoticeAlreadySent(
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.deduction-notice-already-sent",
        "Deduction notice $number has already been recorded as sent",
    )

class DeductionNoticeNotSent(
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.deduction-notice-not-sent",
        "Deduction notice $number has no recorded sending to withdraw",
    )

class DeductionNoticeSentDateInvalid(
    number: DeductionNoticeNumber,
) : BusinessRuleViolation(
        "payables.deduction-notice-sent-date-invalid",
        "Deduction notice $number cannot have been sent before it was raised or after today",
    )
