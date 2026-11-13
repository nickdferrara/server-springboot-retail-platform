package com.retailplatform.receiving

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.locations.api.StoreDirectory
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.receiving.application.DirectDeliveryQueries
import com.retailplatform.receiving.application.ReceiveDirectDelivery
import com.retailplatform.receiving.application.ReceiveDirectDeliveryCommand
import com.retailplatform.receiving.application.SetAgreedCost
import com.retailplatform.receiving.application.SetAgreedCostCommand
import com.retailplatform.receiving.domain.AgreedCostEffectiveInPast
import com.retailplatform.receiving.domain.DeliveryTicket
import com.retailplatform.receiving.domain.DeliveryTicketAlreadyReceived
import com.retailplatform.receiving.domain.DeliveryTicketNumber
import com.retailplatform.receiving.domain.NoAgreedCost
import com.retailplatform.receiving.domain.TicketLine
import com.retailplatform.receiving.domain.UnknownDeliveryParty
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.api.VendorDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Stands in for the locations and vendors modules, which the receiving module asks whether a store or vendor
 * exists: every store and vendor exists except [MISSING].
 */
@TestConfiguration(proxyBeanMethods = false)
class KnownStoresAndVendors {
    @Bean
    fun storeDirectory() =
        object : StoreDirectory {
            override fun exists(storeId: StoreId) = storeId.value != MISSING
        }

    @Bean
    fun vendorDirectory() =
        object : VendorDirectory {
            override fun exists(vendorId: VendorId) = vendorId.value != MISSING
        }

    companion object {
        val MISSING: UUID = UUID.fromString("00000000-0000-7000-8000-000000000000")
    }
}

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class, KnownStoresAndVendors::class)
@WithMockUser(username = "manager", authorities = [Roles.STORE_MANAGER])
class DirectDeliveryModuleTest(
    @Autowired private val setAgreedCost: SetAgreedCost,
    @Autowired private val receiveDirectDelivery: ReceiveDirectDelivery,
    @Autowired private val queries: DirectDeliveryQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)
    private var vendorId = VendorId.generate(clock)
    private var mainStreet = StoreId.generate(clock)
    private var lakeside = StoreId.generate(clock)
    private val cola = ItemId.generate(clock)
    private val chips = ItemId.generate(clock)

    /** Each test has a vendor and stores of its own, so costs agreed in one test are not seen by another. */
    @BeforeEach
    fun parties() {
        vendorId = VendorId.generate(clock)
        mainStreet = StoreId.generate(clock)
        lakeside = StoreId.generate(clock)
    }

    @Test
    fun `a delivery is checked in at the store's own agreed cost and announced`(scenario: Scenario) {
        agree(cola, "1.25", listOf(mainStreet))
        agree(cola, "1.40", listOf(lakeside))
        agree(chips, "0.80", listOf(mainStreet, lakeside))
        var id: DirectDeliveryId? = null

        scenario
            .stimulate(
                Runnable {
                    id =
                        receiveDirectDelivery.handle(
                            command(mainStreet, "T-1001", TicketLine.of(cola, 20, 3), TicketLine.of(chips, 0, 5)),
                        )
                },
            ).andWaitForEventOfType(DirectDeliveryReceived::class.java)
            .matching { it.delivery.ticketNumber == "T-1001" && it.delivery.storeId == mainStreet }
            .toArriveAndVerify { event ->
                event.businessDate shouldBe today
                event.currency shouldBe "USD"
                event.lines.map { it.unitCost } shouldBe listOf(BigDecimal("1.2500"), BigDecimal("0.8000"))
                event.lines.map { it.unitsDelivered to it.unitsReturned } shouldBe listOf(20 to 3, 0 to 5)
            }

        val delivery = queries.delivery(requireNotNull(id))
        delivery.receivedBy shouldBe "manager"
        delivery.receivedOn shouldBe today
        delivery.deliveredCost shouldBe usd("25.00")
        delivery.returnedCost shouldBe usd("7.75")
        delivery.lines.map { it.lineNumber } shouldBe listOf(1, 2)
        queries.deliveriesTo(mainStreet, PageQuery.of()).items.map { it.id } shouldBe listOf(delivery.id)

        // The same ticket number is a different delivery at another store, at that store's cost.
        val other = receiveDirectDelivery.handle(command(lakeside, "T-1001", TicketLine.of(cola, 10)))
        queries.delivery(other).deliveredCost shouldBe usd("14.00")
        shouldThrow<DeliveryTicketAlreadyReceived> {
            receiveDirectDelivery.handle(command(mainStreet, "T-1001", TicketLine.of(cola, 1)))
        }
        shouldThrow<ResourceNotFound> { queries.delivery(DirectDeliveryId.generate(clock)) }
    }

    @Test
    fun `a vendor delivers only what it has an agreed cost for at that store`() {
        agree(cola, "1.25", listOf(mainStreet))

        shouldThrow<NoAgreedCost> {
            receiveDirectDelivery.handle(command(mainStreet, "T-2001", TicketLine.of(cola, 5), TicketLine.of(chips, 5)))
        }
        shouldThrow<NoAgreedCost> { receiveDirectDelivery.handle(command(lakeside, "T-2001", TicketLine.of(cola, 5))) }
        queries.deliveriesTo(lakeside, PageQuery.of()).total shouldBe 0
    }

    @Test
    fun `a cost takes effect on its date and is not set in the past or for an unknown vendor or store`() {
        agree(cola, "1.25", listOf(mainStreet))
        agree(cola, "1.35", listOf(mainStreet), effectiveFrom = today.plusDays(7))
        // Setting the same day again corrects it.
        agree(cola, "1.30", listOf(mainStreet), effectiveFrom = today.plusDays(7))

        queries.agreedCosts(vendorId, mainStreet, null).single().unitCost shouldBe usd("1.25")
        queries.agreedCosts(vendorId, mainStreet, today.plusDays(7)).single().unitCost shouldBe usd("1.30")
        queries.agreedCosts(vendorId, mainStreet, today.minusDays(1)) shouldBe emptyList()

        asBuyer()
        shouldThrow<AgreedCostEffectiveInPast> {
            setAgreedCost.handle(
                cost(cola, "1.00", today.minusDays(1), listOf(mainStreet)),
            )
        }
        val missing = KnownStoresAndVendors.MISSING
        shouldThrow<UnknownDeliveryParty> {
            setAgreedCost.handle(cost(cola, "1.00", today, listOf(mainStreet, StoreId.of(missing))))
        }
        shouldThrow<UnknownDeliveryParty> {
            setAgreedCost.handle(cost(cola, "1.00", today, listOf(mainStreet)).copy(vendorId = VendorId.of(missing)))
        }
    }

    @Test
    fun `buyers set costs and store managers check deliveries in`() {
        shouldThrow<AccessDeniedException> { setAgreedCost.handle(cost(cola, "1.00", today, listOf(mainStreet))) }
        asBuyer()
        shouldThrow<AccessDeniedException> {
            receiveDirectDelivery.handle(command(mainStreet, "T-3001", TicketLine.of(cola, 1)))
        }
    }

    private fun agree(
        itemId: ItemId,
        unitCost: String,
        stores: List<StoreId>,
        effectiveFrom: BusinessDate = today,
    ) {
        asBuyer()
        setAgreedCost.handle(cost(itemId, unitCost, effectiveFrom, stores))
        actingAs("manager", Roles.STORE_MANAGER)
    }

    private fun cost(
        itemId: ItemId,
        unitCost: String,
        effectiveFrom: BusinessDate,
        stores: List<StoreId>,
    ) = SetAgreedCostCommand(vendorId, itemId, stores, usd(unitCost), effectiveFrom)

    private fun command(
        storeId: StoreId,
        ticket: String,
        vararg lines: TicketLine,
    ) = ReceiveDirectDeliveryCommand(DeliveryTicket(vendorId, storeId, DeliveryTicketNumber.of(ticket)), lines.toList())

    private fun asBuyer() = actingAs("buyer", Roles.BUYER)

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}
