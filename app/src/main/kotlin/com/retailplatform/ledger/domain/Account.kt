package com.retailplatform.ledger.domain

import com.retailplatform.shared.BusinessRuleViolation

/**
 * An account string in the chart of accounts: company, natural account, location, and department, for example
 * `01-4010-0101-52`.
 */
@JvmInline
value class AccountNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{2}-[0-9]{4}-[0-9]{4}-[0-9]{2}")

        /** @throws IllegalArgumentException unless [value] has the four segments `NN-NNNN-NNNN-NN`. */
        fun of(value: String): AccountNumber {
            require(FORMAT.matches(value)) { "Not an account number: '$value'" }
            return AccountNumber(value)
        }
    }
}

/** Which financial statement section an account belongs to, and so which side increases it. */
enum class AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE,
}

/** An account journal entries can be posted to. */
@ConsistentCopyVisibility
data class Account private constructor(
    val number: AccountNumber,
    val name: String,
    val type: AccountType,
) {
    companion object {
        const val MAX_NAME_LENGTH = 100

        /** @throws IllegalArgumentException if [name] is blank or longer than [MAX_NAME_LENGTH] once trimmed. */
        fun of(
            number: AccountNumber,
            name: String,
            type: AccountType,
        ): Account {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "An account name cannot be blank" }
            require(trimmed.length <= MAX_NAME_LENGTH) { "An account name has at most $MAX_NAME_LENGTH characters" }
            return Account(number, trimmed, type)
        }
    }
}

/**
 * The roles accounts play in posting rules. A rule names the role; which account fills it is data, so the chart
 * of accounts can change without changing a rule.
 */
enum class PostingAccount {
    /** Merchandise held at a warehouse. */
    WAREHOUSE_INVENTORY,

    /** Owed to vendors for goods received and not yet invoiced. */
    ACCRUED_PAYABLES,

    /** Merchandise held at stores. */
    STORE_INVENTORY,

    /** Owed to vendors for goods delivered straight to stores and not yet invoiced. */
    ACCRUED_DIRECT_DELIVERY_PAYABLES,

    /** Owed to vendors on matched invoices. */
    ACCOUNTS_PAYABLE,

    /** The difference between what a vendor billed and the purchase order cost that was accrued. */
    PURCHASE_PRICE_VARIANCE,

    /** The bank account vendor payments are drawn on. */
    BANK,

    /** Early-payment discounts taken on vendor payments. */
    PURCHASE_DISCOUNTS_TAKEN,
}

class AccountNumberAlreadyInUse(
    number: AccountNumber,
) : BusinessRuleViolation("ledger.account-number-in-use", "Account $number already exists")

class UnknownAccount(
    number: AccountNumber,
) : BusinessRuleViolation("ledger.unknown-account", "Account $number is not in the chart of accounts")

class PostingAccountNotMapped(
    role: PostingAccount,
) : BusinessRuleViolation("ledger.posting-account-not-mapped", "No account is assigned to $role")
