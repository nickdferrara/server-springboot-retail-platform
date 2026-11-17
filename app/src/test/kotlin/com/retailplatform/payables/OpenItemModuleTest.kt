package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.adapter.`in`.metrics.OpenItemMonitor
import com.retailplatform.payables.application.AcceptUnderBilledDirectDeliveryInvoice
import com.retailplatform.payables.application.DeductionNoticeQueries
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.ItemAge
import com.retailplatform.payables.application.NoticeSent
import com.retailplatform.payables.application.OpenItemAgeing
import com.retailplatform.payables.application.OpenItemFilter
import com.retailplatform.payables.application.OpenItemKind
import com.retailplatform.payables.application.OpenItemQueries
import com.retailplatform.payables.application.RecordDeductionDispute
import com.retailplatform.payables.application.RecordDeductionNoticeSent
import com.retailplatform.payables.application.RecordDeliveryCorrection
import com.retailplatform.payables.application.RecordDeliveryCorrectionCommand
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.RecordLateBill
import com.retailplatform.payables.application.ShortPayDirectDeliveryInvoice
import com.retailplatform.payables.application.WriteOffDeliveryAdjustment
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DeliveryAdjustmentId
import com.retailplatform.payables.domain.DisputeClaim
import com.retailplatform.payables.domain.LateBillDetails
import com.retailplatform.payables.domain.NoticeChannel
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.TicketUnitChange
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.time.Clock

/** Ageing: what waits for accounting on direct deliveries, how long it has waited, and what is overdue. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class OpenItemModuleTest(
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val recordDeliveryCorrection: RecordDeliveryCorrection,
    @Autowired private val enterDirectDeliveryInvoice: EnterDirectDeliveryInvoice,
    @Autowired private val acceptUnderBilled: AcceptUnderBilledDirectDeliveryInvoice,
    @Autowired private val shortPayInvoice: ShortPayDirectDeliveryInvoice,
    @Autowired private val notices: DeductionNoticeQueries,
    @Autowired private val recordLateBill: RecordLateBill,
    @Autowired private val recordDispute: RecordDeductionDispute,
    @Autowired private val writeOffAdjustment: WriteOffDeliveryAdjustment,
    @Autowired private val recordSent: RecordDeductionNoticeSent,
    @Autowired private val openItems: OpenItemQueries,
    @Autowired private val ageing: OpenItemAgeing,
    @Autowired private val monitor: OpenItemMonitor,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)
    private val cola = ItemId.generate(clock)

    @Test
    fun `open adjustments, late bills, and disputes are listed together, oldest first, and counted`() {
        val vendorId = VendorId.generate(clock)
        // A late bill on a ticket closed under-billed, and a dispute and an old adjustment on one paid short.
        val closed = invoiced(vendorId, "T-150", "DSD-150", received = 20, billed = 15)
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        acceptUnderBilled.handle(closed.second, "Vendor will not bill the rest")
        val shortPaid = invoiced(vendorId, "T-151", "DSD-151", received = 18, billed = 20)
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        shortPayInvoice.handle(shortPaid.second, "Store count confirmed with the driver")
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        val lateBill =
            recordLateBill.handle(
                LateBillDetails(billed(closed.first), number("DSD-150B"), today, usd("6.25")),
            )
        val dispute =
            recordDispute.handle(
                notices.forInvoice(shortPaid.second).id,
                DisputeClaim.of(today, number("CLAIM-151"), "We delivered all 20"),
            )
        // The correction that raised the adjustment was made 40 days ago.
        recordDeliveryCorrection.handle(
            RecordDeliveryCorrectionCommand(
                shortPaid.first.copy(receiptNumber = "100152", receivedOn = today.minusDays(40)),
                delivered = listOf(TicketUnitChange(cola, 1, usd("1.25"))),
                returned = emptyList(),
            ),
        )

        val all = openItems.list(OpenItemFilter(vendorId, overdueOnly = false), PageQuery.of()).items
        // The short-pay also raised a deduction notice, which waits to be sent.
        all.map { it.item.kind } shouldBe
            listOf(OpenItemKind.ADJUSTMENT, OpenItemKind.NOTICE, OpenItemKind.LATE_BILL, OpenItemKind.DISPUTE)
        all.first().age shouldBe ItemAge(days = 40, overdue = true)
        all.first().item.amount shouldBe usd("1.25")
        all.first().item.ticketNumber shouldBe "T-151"
        all.drop(1).map { it.age } shouldBe listOf(ItemAge(0, false), ItemAge(0, false), ItemAge(0, false))
        val notice = notices.forInvoice(shortPaid.second)
        all.drop(1).map { it.item.id } shouldBe listOf(notice.id.value, lateBill.value, dispute.value)
        all[1].item.amount shouldBe usd("2.50")
        all.single { it.item.kind == OpenItemKind.DISPUTE }.item.amount shouldBe usd("2.50")
        narrowedByKind(vendorId) shouldBe listOf(listOf(notice.id.value), 3L, 0L)
        val overdue = openItems.list(OpenItemFilter(vendorId, overdueOnly = true), PageQuery.of())
        overdue.total shouldBe 1
        overdue.items
            .single()
            .item.kind shouldBe OpenItemKind.ADJUSTMENT
        // Without a vendor the list covers every vendor.
        openItems.list(OpenItemFilter(null, overdueOnly = false), PageQuery.of()).total shouldBeGreaterThanOrEqual 4

        // Once the notice is recorded as sent it no longer waits.
        (gaugeAfterCheck("open", "notice") >= 1.0) shouldBe true
        recordSent.handle(notice.id, NoticeSent(today, NoticeChannel.EMAIL, "ar@vendor.example"))
        openItems
            .list(OpenItemFilter(vendorId, overdueOnly = false), PageQuery.of())
            .items
            .none { it.item.kind == OpenItemKind.NOTICE } shouldBe true
        ageing.ageOf(notices.find(notice.id)) shouldBe null

        // The gauges follow the counts; a settled item is no longer counted.
        val before = monitor.check().getValue(OpenItemKind.ADJUSTMENT)
        before.overdue shouldBeGreaterThanOrEqual 1
        gauge("overdue", "adjustment") shouldBe before.overdue.toDouble()
        (gauge("open", "late-bill") >= 1.0) shouldBe true
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        writeOffAdjustment.handle(DeliveryAdjustmentId.of(all.first().item.id), "Vendor will not bill it")
        monitor.check().getValue(OpenItemKind.ADJUSTMENT).overdue shouldBe before.overdue - 1
        gauge("overdue", "adjustment") shouldBe (before.overdue - 1).toDouble()
        openItems.list(OpenItemFilter(vendorId, overdueOnly = true), PageQuery.of()).total shouldBe 0
    }

    @Test
    fun `an item is overdue once it has waited longer than its kind allows`() {
        val allowed =
            mapOf(
                OpenItemKind.ADJUSTMENT to 30L,
                OpenItemKind.LATE_BILL to 30L,
                OpenItemKind.DISPUTE to 30L,
                OpenItemKind.NOTICE to 7L,
            )
        allowed.keys shouldBe OpenItemKind.entries.toSet()
        allowed.forEach { (kind, days) ->
            ageing.ageOf(kind, today.minusDays(days)) shouldBe ItemAge(days, overdue = false)
            ageing.ageOf(kind, today.minusDays(days + 1)) shouldBe ItemAge(days + 1, overdue = true)
            ageing.overdueBefore(kind) shouldBe today.minusDays(days)
        }
    }

    /**
     * A clerk looks at notices alone and a manager at what needs deciding: the notices to send, how many items
     * wait for a decision, and how many notices are overdue.
     */
    private fun narrowedByKind(vendorId: VendorId): List<Any> {
        val toSend = OpenItemFilter(vendorId, overdueOnly = false, kinds = setOf(OpenItemKind.NOTICE))
        val toDecide = toSend.copy(kinds = setOf(OpenItemKind.ADJUSTMENT, OpenItemKind.LATE_BILL, OpenItemKind.DISPUTE))
        return listOf(
            openItems.list(toSend, PageQuery.of()).items.map { it.item.id },
            openItems.list(toDecide, PageQuery.of()).total,
            openItems.list(toSend.copy(overdueOnly = true), PageQuery.of()).total,
        )
    }

    private fun gaugeAfterCheck(
        state: String,
        kind: String,
    ): Double {
        monitor.check()
        return gauge(state, kind)
    }

    private fun gauge(
        state: String,
        kind: String,
    ): Double =
        meters
            .get("retail.payables.open-items.$state")
            .tag("kind", kind)
            .gauge()
            .value()

    /** A ticket of [received] colas at 1.25, billed for [billed]; leaves the clerk signed in. */
    private fun invoiced(
        vendorId: VendorId,
        ticketNumber: String,
        invoiceNumber: String,
        received: Int,
        billed: Int,
    ): Pair<DeliveredTicket, InvoiceId> {
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        val storeId = StoreId.generate(clock)
        val ticket = DeliveredTicket(DirectDeliveryId.generate(clock), "100150", vendorId, storeId, ticketNumber, today)
        recordDirectDelivery.handle(
            RecordDirectDeliveryCommand(ticket, listOf(TicketAmountLine.of(cola, received, usd("1.25"))), emptyList()),
        )
        val invoiceId =
            enterDirectDeliveryInvoice.handle(
                TicketInvoiceDetails(
                    billed(ticket),
                    number(invoiceNumber),
                    today,
                    listOf(TicketAmountLine.of(cola, billed, usd("1.25"))),
                ),
            )
        return ticket to invoiceId
    }

    private fun billed(ticket: DeliveredTicket) = BilledTicket(ticket.vendorId, ticket.storeId, ticket.ticketNumber)

    private fun number(value: String) = VendorInvoiceNumber.of(value)

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}
