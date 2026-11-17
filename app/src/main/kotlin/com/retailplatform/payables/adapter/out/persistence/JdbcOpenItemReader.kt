package com.retailplatform.payables.adapter.out.persistence

import com.retailplatform.payables.application.OpenItem
import com.retailplatform.payables.application.OpenItemCount
import com.retailplatform.payables.application.OpenItemFilter
import com.retailplatform.payables.application.OpenItemKind
import com.retailplatform.payables.application.OpenItemReader
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/** Reads open adjustments, late bills, disputes, and unsent notices as one list, straight from their tables. */
@Repository
class JdbcOpenItemReader(
    private val jdbc: JdbcClient,
) : OpenItemReader {
    override fun find(
        filter: OpenItemFilter,
        overdueBefore: Map<OpenItemKind, BusinessDate>,
        query: PageQuery,
    ): Page<OpenItem> {
        val where = "WHERE (:vendorId::uuid IS NULL OR vendor_id = :vendorId::uuid) AND (NOT :overdueOnly OR $OVERDUE)"
        val items =
            jdbc
                .sql("SELECT * FROM ($OPEN_ITEMS) items $where ORDER BY open_since, id LIMIT :limit OFFSET :offset")
                .filtered(filter, overdueBefore)
                .param("limit", query.size)
                .param("offset", query.offset)
                .query { row, _ ->
                    OpenItem(
                        kind = OpenItemKind.valueOf(row.getString("kind")),
                        id = row.getObject("id", UUID::class.java),
                        vendorId = VendorId.of(row.getObject("vendor_id", UUID::class.java)),
                        storeId = StoreId.of(row.getObject("store_id", UUID::class.java)),
                        ticketNumber = row.getString("ticket_number"),
                        openSince = BusinessDate(row.getObject("open_since", LocalDate::class.java)),
                        amount = Money.of(row.getBigDecimal("amount"), Currency.getInstance(row.getString("currency"))),
                    )
                }.list()
        val total =
            jdbc
                .sql("SELECT count(*) FROM ($OPEN_ITEMS) items $where")
                .filtered(filter, overdueBefore)
                .query(Long::class.java)
                .single()
        return Page(items, query.page, query.size, total)
    }

    override fun counts(overdueBefore: Map<OpenItemKind, BusinessDate>): Map<OpenItemKind, OpenItemCount> {
        val found =
            jdbc
                .sql(
                    "SELECT kind, count(*) AS open, count(*) FILTER (WHERE $OVERDUE) AS overdue " +
                        "FROM ($OPEN_ITEMS) items GROUP BY kind",
                ).overdue(overdueBefore)
                .query { row, _ ->
                    OpenItemKind.valueOf(row.getString("kind")) to
                        OpenItemCount(row.getLong("open"), row.getLong("overdue"))
                }.list()
                .toMap()
        return OpenItemKind.entries.associateWith { found[it] ?: OpenItemCount(0, 0) }
    }

    private fun JdbcClient.StatementSpec.filtered(
        filter: OpenItemFilter,
        overdueBefore: Map<OpenItemKind, BusinessDate>,
    ): JdbcClient.StatementSpec =
        param("vendorId", filter.vendorId?.value?.toString())
            .param("overdueOnly", filter.overdueOnly)
            .overdue(overdueBefore)

    private fun JdbcClient.StatementSpec.overdue(before: Map<OpenItemKind, BusinessDate>): JdbcClient.StatementSpec =
        param("adjustmentBefore", before.getValue(OpenItemKind.ADJUSTMENT).value)
            .param("lateBillBefore", before.getValue(OpenItemKind.LATE_BILL).value)
            .param("disputeBefore", before.getValue(OpenItemKind.DISPUTE).value)
            .param("noticeBefore", before.getValue(OpenItemKind.NOTICE).value)

    private companion object {
        /** Every item still waiting, with the day it started to wait and the amount it is about. */
        val OPEN_ITEMS =
            """
            SELECT 'ADJUSTMENT' AS kind, id, vendor_id, store_id, ticket_number, recorded_on AS open_since,
                   amount, currency
            FROM payables.direct_delivery_adjustment WHERE status = 'OPEN'
            UNION ALL
            SELECT 'LATE_BILL', id, vendor_id, store_id, ticket_number, recorded_on, amount, currency
            FROM payables.direct_delivery_late_bill WHERE status = 'OPEN'
            UNION ALL
            SELECT 'DISPUTE', id, vendor_id, store_id, ticket_number, received_on, deduction, currency
            FROM payables.deduction_dispute WHERE status = 'OPEN'
            UNION ALL
            SELECT 'NOTICE', id, vendor_id, store_id, ticket_number, raised_on, amount, currency
            FROM payables.deduction_notice WHERE sent_on IS NULL
            """.trimIndent()

        /** Whether an item has been open since before its kind's overdue day. */
        val OVERDUE =
            """
            ((kind = 'ADJUSTMENT' AND open_since < :adjustmentBefore)
              OR (kind = 'LATE_BILL' AND open_since < :lateBillBefore)
              OR (kind = 'DISPUTE' AND open_since < :disputeBefore)
              OR (kind = 'NOTICE' AND open_since < :noticeBefore))
            """.trimIndent()
    }
}
