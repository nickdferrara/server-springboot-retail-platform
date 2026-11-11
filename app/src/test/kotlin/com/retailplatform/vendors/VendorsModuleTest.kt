package com.retailplatform.vendors

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.api.VendorDirectory
import com.retailplatform.vendors.api.events.VendorCreated
import com.retailplatform.vendors.api.events.VendorTermsChanged
import com.retailplatform.vendors.application.ChangeVendorTerms
import com.retailplatform.vendors.application.ChangeVendorTermsCommand
import com.retailplatform.vendors.application.CreateVendor
import com.retailplatform.vendors.application.CreateVendorCommand
import com.retailplatform.vendors.application.UpdateVendorDetails
import com.retailplatform.vendors.application.UpdateVendorDetailsCommand
import com.retailplatform.vendors.application.VendorQueries
import com.retailplatform.vendors.domain.PaymentTerms
import com.retailplatform.vendors.domain.PostalAddress
import com.retailplatform.vendors.domain.VendorContact
import com.retailplatform.vendors.domain.VendorName
import com.retailplatform.vendors.domain.VendorNumber
import com.retailplatform.vendors.domain.VendorNumberAlreadyInUse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.context.support.WithMockUser
import java.time.Clock

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(authorities = [Roles.MASTER_DATA_EDITOR])
class VendorsModuleTest(
    @Autowired private val createVendor: CreateVendor,
    @Autowired private val changeVendorTerms: ChangeVendorTerms,
    @Autowired private val updateVendorDetails: UpdateVendorDetails,
    @Autowired private val queries: VendorQueries,
    @Autowired private val directory: VendorDirectory,
    @Autowired private val clock: Clock,
) {
    private val address = PostalAddress.of("1 Supply Way", null, "Springfield", "IL", "62701", "US")

    @Test
    fun `creating a vendor saves it with its contacts and publishes VendorCreated`(scenario: Scenario) {
        val contacts =
            listOf(
                VendorContact.of("Dana Lee", "dana@example.com", null),
                VendorContact.of("AP desk", null, "555-0100"),
            )

        scenario
            .stimulate(Runnable { createVendor.handle(command("1001", "Acme Snacks", contacts)) })
            .andWaitForEventOfType(VendorCreated::class.java)
            .matching { it.vendorNumber == "1001" }
            .toArriveAndVerify { event ->
                val saved = queries.find(event.vendorId)
                saved.name.value shouldBe "Acme Snacks"
                saved.contacts shouldBe contacts
                saved.version shouldBe 1
                directory.exists(event.vendorId) shouldBe true
            }
    }

    @Test
    fun `changing payment terms publishes VendorTermsChanged and bumps the version`(scenario: Scenario) {
        val vendorId = createVendor.handle(command("1002", "Bottling Co"))
        val newTerms = PaymentTerms.withDiscount(Percentage.of("2"), 10, 30)

        scenario
            .stimulate(Runnable { changeVendorTerms.handle(ChangeVendorTermsCommand(vendorId, newTerms)) })
            .andWaitForEventOfType(VendorTermsChanged::class.java)
            .matching { it.vendorId == vendorId }
            .toArriveAndVerify { event ->
                event.paymentTerms.discountDays shouldBe 10
                val saved = queries.find(vendorId)
                saved.paymentTerms shouldBe newTerms
                saved.version shouldBe 2
            }
    }

    @Test
    fun `updating details replaces the name, address, and contacts`() {
        val vendorId = createVendor.handle(command("1003", "Old Name", listOf(VendorContact.of("Gone", null, null))))
        val newAddress = PostalAddress.of("9 Dock Road", "Unit 2", "Peoria", "IL", "61602", "US")

        updateVendorDetails.handle(
            UpdateVendorDetailsCommand(vendorId, VendorName.of("New Name"), newAddress, emptyList()),
        )

        val saved = queries.find(vendorId)
        saved.name.value shouldBe "New Name"
        saved.remitTo shouldBe newAddress
        saved.contacts shouldBe emptyList()
    }

    @Test
    fun `a vendor number can be used only once`() {
        createVendor.handle(command("1004", "First"))

        shouldThrow<VendorNumberAlreadyInUse> { createVendor.handle(command("1004", "Second")) }
    }

    @Test
    fun `vendors are listed by number, a page at a time`() {
        listOf("1903", "1901", "1902").forEach { createVendor.handle(command(it, "Vendor $it")) }

        val all = queries.list(PageQuery.of(size = 200))
        val firstTwo = queries.list(PageQuery.of(page = 0, size = 2))

        all.items.map { it.number.value } shouldContainInOrder listOf("1901", "1902", "1903")
        all.total shouldBe all.items.size.toLong()
        firstTwo.items.size shouldBe 2
        firstTwo.total shouldBe all.total
    }

    @Test
    fun `a vendor that does not exist cannot be found or changed`() {
        val unknown = VendorId.generate(clock)

        directory.exists(unknown) shouldBe false
        shouldThrow<ResourceNotFound> { queries.find(unknown) }
        shouldThrow<ResourceNotFound> {
            changeVendorTerms.handle(
                ChangeVendorTermsCommand(unknown, PaymentTerms.net(15)),
            )
        }
        shouldThrow<ResourceNotFound> {
            updateVendorDetails.handle(
                UpdateVendorDetailsCommand(unknown, VendorName.of("Nobody"), address, emptyList()),
            )
        }
    }

    @Test
    @WithMockUser(authorities = [Roles.BUYER])
    fun `only a master data editor may change vendors`() {
        shouldThrow<AccessDeniedException> { createVendor.handle(command("1005", "Not allowed")) }
    }

    private fun command(
        number: String,
        name: String,
        contacts: List<VendorContact> = emptyList(),
    ) = CreateVendorCommand(VendorNumber.of(number), VendorName.of(name), PaymentTerms.net(30), address, contacts)
}
