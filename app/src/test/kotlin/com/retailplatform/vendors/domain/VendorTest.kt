package com.retailplatform.vendors.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Percentage
import com.retailplatform.vendors.api.events.PaymentTermsFacts
import com.retailplatform.vendors.api.events.VendorCreated
import com.retailplatform.vendors.api.events.VendorTermsChanged
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class VendorTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-11T03:00:00Z"), ZoneId.of("America/Chicago"))
    private val address = PostalAddress.of("1 Supply Way", " ", "Springfield", "IL", "62701", "US")
    private val net30 = PaymentTerms.net(30)
    private val twoTenNet30 = PaymentTerms.withDiscount(Percentage.of("2"), 10, 30)

    private fun vendor(terms: PaymentTerms = net30) =
        Vendor.create(VendorNumber.of("000123"), VendorName.of(" Acme Snacks "), terms, address, emptyList(), clock)

    @Test
    fun `creating a vendor raises VendorCreated dated in the company time zone`() {
        val vendor = vendor(twoTenNet30)

        val event = vendor.releaseEvents().single() as VendorCreated

        event.vendorId shouldBe vendor.id
        event.vendorNumber shouldBe "000123"
        event.vendorName shouldBe "Acme Snacks"
        event.paymentTerms shouldBe PaymentTermsFacts(30, BigDecimal("2.0000"), 10)
        event.businessDate shouldBe BusinessDate.parse("2026-11-10")
        vendor.version shouldBe 0
    }

    @Test
    fun `changing the payment terms raises VendorTermsChanged once`() {
        val vendor = vendor().also { it.releaseEvents() }

        vendor.changePaymentTerms(twoTenNet30, clock)
        vendor.changePaymentTerms(twoTenNet30, clock)

        val event = vendor.releaseEvents().single() as VendorTermsChanged
        event.paymentTerms shouldBe PaymentTermsFacts(30, BigDecimal("2.0000"), 10)
        vendor.paymentTerms shouldBe twoTenNet30
    }

    @Test
    fun `updating details replaces them without raising an event`() {
        val vendor = vendor().also { it.releaseEvents() }
        val contact = VendorContact.of("Dana Lee", "dana@example.com", null)

        vendor.updateDetails(VendorName.of("Acme Foods"), address, listOf(contact))

        vendor.name.value shouldBe "Acme Foods"
        vendor.contacts shouldBe listOf(contact)
        vendor.releaseEvents().shouldBeEmpty()
    }

    @Test
    fun `payment terms need a discount window that ends before the due date`() {
        twoTenNet30.toString() shouldBe "2% 10, net 30"
        net30.toString() shouldBe "net 30"
        shouldThrow<IllegalArgumentException> { PaymentTerms.net(366) }
        shouldThrow<IllegalArgumentException> { PaymentTerms.net(-1) }
        shouldThrow<IllegalArgumentException> { PaymentTerms.withDiscount(Percentage.of("2"), 30, 30) }
        shouldThrow<IllegalArgumentException> { PaymentTerms.withDiscount(Percentage.of("0"), 10, 30) }
    }

    @Test
    fun `an address trims its parts, drops a blank second line, and needs an ISO country`() {
        address.line2 shouldBe null
        PostalAddress.of(" 1 Supply Way ", "Dock 4", "Springfield", "IL", "62701", "US").line1 shouldBe "1 Supply Way"
        shouldThrow<IllegalArgumentException> { PostalAddress.of("", null, "Springfield", "IL", "62701", "US") }
        shouldThrow<IllegalArgumentException> {
            PostalAddress.of(
                "1 Supply Way",
                null,
                "Springfield",
                "IL",
                "62701",
                "usa",
            )
        }
        shouldThrow<IllegalArgumentException> {
            PostalAddress.of("1 Supply Way", null, "Springfield", "IL", "6".repeat(13), "US")
        }
    }

    @Test
    fun `a contact needs a name and a plausible email`() {
        VendorContact.of(" Dana Lee ", " ", "555-0100") shouldBe VendorContact.of("Dana Lee", null, "555-0100")
        shouldThrow<IllegalArgumentException> { VendorContact.of(" ", null, null) }
        shouldThrow<IllegalArgumentException> { VendorContact.of("Dana Lee", "not-an-email", null) }
        shouldThrow<IllegalArgumentException> { VendorContact.of("x".repeat(101), null, null) }
    }

    @Test
    fun `vendor numbers and names are validated`() {
        VendorNumber.of("0000000042").toString() shouldBe "0000000042"
        shouldThrow<IllegalArgumentException> { VendorNumber.of("12345678901") }
        shouldThrow<IllegalArgumentException> { VendorNumber.of("A12") }
        shouldThrow<IllegalArgumentException> { VendorName.of("  ") }
        shouldThrow<IllegalArgumentException> { VendorName.of("x".repeat(101)) }
        VendorNumberAlreadyInUse(VendorNumber.of("42")).code shouldBe "vendors.vendor-number-in-use"
    }
}
