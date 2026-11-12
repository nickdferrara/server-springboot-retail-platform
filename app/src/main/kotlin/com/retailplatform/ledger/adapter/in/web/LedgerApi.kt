package com.retailplatform.ledger.adapter.`in`.web

import com.retailplatform.ledger.application.AssignPostingAccount
import com.retailplatform.ledger.application.CreateAccount
import com.retailplatform.ledger.application.LedgerQueries
import com.retailplatform.ledger.domain.Account
import com.retailplatform.ledger.domain.AccountNumber
import com.retailplatform.ledger.domain.AccountType
import com.retailplatform.ledger.domain.FiscalPeriod
import com.retailplatform.ledger.domain.JournalEntry
import com.retailplatform.ledger.domain.PostingAccount
import com.retailplatform.platform.web.MoneyDto
import com.retailplatform.platform.web.PageResponse
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.platform.web.pageQuery
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.JournalEntryId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

private const val ACCOUNT_NUMBER = "[0-9]{2}-[0-9]{4}-[0-9]{4}-[0-9]{2}"
private const val ACCOUNT_NUMBER_MESSAGE = "must be four segments of digits, such as 01-4010-0101-52"

data class AccountDto(
    @field:NotBlank @field:Pattern(regexp = ACCOUNT_NUMBER, message = ACCOUNT_NUMBER_MESSAGE)
    val number: String?,
    @field:NotBlank @field:Size(max = Account.MAX_NAME_LENGTH)
    val name: String?,
    @field:NotNull
    val type: AccountType?,
) {
    fun toDomain(): Account =
        mapRequest { Account.of(AccountNumber.of(requireNotNull(number)), requireNotNull(name), requireNotNull(type)) }

    companion object {
        fun from(account: Account) = AccountDto(account.number.value, account.name, account.type)
    }
}

data class PostingAccountDto(
    val role: PostingAccount,
    @field:NotBlank @field:Pattern(regexp = ACCOUNT_NUMBER, message = ACCOUNT_NUMBER_MESSAGE)
    val accountNumber: String?,
)

data class AssignPostingAccountRequest(
    @field:NotBlank @field:Pattern(regexp = ACCOUNT_NUMBER, message = ACCOUNT_NUMBER_MESSAGE)
    val accountNumber: String?,
)

data class FiscalPeriodResponse(
    val fiscalYear: Int,
    val period: Int,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
) {
    companion object {
        fun from(period: FiscalPeriod) =
            FiscalPeriodResponse(period.fiscalYear, period.period, period.startsOn.value, period.endsOn.value)
    }
}

data class JournalLineResponse(
    val lineNumber: Int,
    val accountNumber: String,
    val side: String,
    val amount: MoneyDto,
)

data class JournalEntryResponse(
    val id: String,
    val number: String,
    val journal: String,
    val businessDate: LocalDate,
    val fiscalYear: Int,
    val fiscalPeriod: Int,
    val sourceModule: String,
    val sourceEventId: String?,
    val sourceDocument: String,
    val description: String,
    val amount: MoneyDto,
    val lines: List<JournalLineResponse>,
) {
    companion object {
        fun from(entry: JournalEntry) =
            JournalEntryResponse(
                id = entry.id.toString(),
                number = entry.number.value,
                journal = entry.content.journal.name,
                businessDate = entry.content.businessDate.value,
                fiscalYear = entry.period.fiscalYear,
                fiscalPeriod = entry.period.period,
                sourceModule = entry.content.source.module,
                sourceEventId =
                    entry.content.source.eventId
                        ?.toString(),
                sourceDocument = entry.content.source.document,
                description = entry.content.description,
                amount = MoneyDto.from(entry.amount),
                lines =
                    entry.lines.mapIndexed { index, line ->
                        JournalLineResponse(index + 1, line.account.value, line.side.name, MoneyDto.from(line.amount))
                    },
            )
    }
}

data class TrialBalanceLineResponse(
    val accountNumber: String,
    val accountName: String,
    val accountType: AccountType,
    val debits: MoneyDto,
    val credits: MoneyDto,
    val balance: MoneyDto,
)

data class TrialBalanceResponse(
    val asOf: LocalDate,
    val balanced: Boolean,
    val accounts: List<TrialBalanceLineResponse>,
)

@RestController
@RequestMapping("/api/v1/ledger")
class LedgerController(
    private val createAccount: CreateAccount,
    private val assignPostingAccount: AssignPostingAccount,
    private val queries: LedgerQueries,
) {
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @Valid @RequestBody request: AccountDto,
    ): AccountDto {
        val account = request.toDomain()
        createAccount.handle(account)
        return AccountDto.from(account)
    }

    @GetMapping("/accounts")
    fun accounts(): List<AccountDto> = queries.accounts().map(AccountDto::from)

    /** Which account fills each role in the posting rules. */
    @GetMapping("/posting-accounts")
    fun postingAccounts(): List<PostingAccountDto> =
        queries.postingAccounts().map { (role, number) -> PostingAccountDto(role, number.value) }.sortedBy { it.role }

    @PutMapping("/posting-accounts/{role}")
    fun assignPostingAccount(
        @PathVariable role: PostingAccount,
        @Valid @RequestBody request: AssignPostingAccountRequest,
    ): PostingAccountDto {
        val number = mapRequest { AccountNumber.of(requireNotNull(request.accountNumber)) }
        assignPostingAccount.handle(role, number)
        return PostingAccountDto(role, number.value)
    }

    @GetMapping("/fiscal-periods")
    fun fiscalPeriods(
        @RequestParam fiscalYear: Int,
    ): List<FiscalPeriodResponse> = queries.periods(fiscalYear).map(FiscalPeriodResponse::from)

    /** Journal entries with the newest first. */
    @GetMapping("/journal-entries")
    fun journalEntries(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): PageResponse<JournalEntryResponse> =
        PageResponse.from(queries.entries(pageQuery(page, size)), JournalEntryResponse::from)

    @GetMapping("/journal-entries/{id}")
    fun journalEntry(
        @PathVariable id: String,
    ): JournalEntryResponse = JournalEntryResponse.from(queries.entry(mapRequest { JournalEntryId.parse(id) }))

    /** Debits, credits, and balance per account from every entry dated on or before [asOf]. */
    @GetMapping("/trial-balance")
    fun trialBalance(
        @RequestParam asOf: LocalDate,
    ): TrialBalanceResponse {
        val trialBalance = queries.trialBalance(BusinessDate(asOf))
        return TrialBalanceResponse(
            asOf = asOf,
            balanced = trialBalance.isBalanced,
            accounts =
                trialBalance.accounts.map {
                    TrialBalanceLineResponse(
                        accountNumber = it.account.number.value,
                        accountName = it.account.name,
                        accountType = it.account.type,
                        debits = MoneyDto.from(it.debits),
                        credits = MoneyDto.from(it.credits),
                        balance = MoneyDto.from(it.balance),
                    )
                },
        )
    }
}
