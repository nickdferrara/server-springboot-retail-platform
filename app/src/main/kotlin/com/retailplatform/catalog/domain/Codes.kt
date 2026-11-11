package com.retailplatform.catalog.domain

private val CODE_FORMAT = Regex("[A-Z0-9]{1,10}")
private const val MAX_NAME_LENGTH = 60

/** Identifies a department, the top level of the item hierarchy: 1 to 10 upper-case letters or digits. */
@JvmInline
value class DepartmentCode private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        /** @throws IllegalArgumentException unless [value] is 1 to 10 upper-case letters or digits. */
        fun of(value: String): DepartmentCode {
            require(CODE_FORMAT.matches(value)) { "Not a department code: '$value'" }
            return DepartmentCode(value)
        }
    }
}

/** Identifies a category within the item hierarchy: 1 to 10 upper-case letters or digits. */
@JvmInline
value class CategoryCode private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        /** @throws IllegalArgumentException unless [value] is 1 to 10 upper-case letters or digits. */
        fun of(value: String): CategoryCode {
            require(CODE_FORMAT.matches(value)) { "Not a category code: '$value'" }
            return CategoryCode(value)
        }
    }
}

/** The name of a department, category, or unit of measure: not blank, at most 60 characters. */
@JvmInline
value class CatalogName private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = MAX_NAME_LENGTH

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): CatalogName {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A name cannot be blank" }
            require(trimmed.length <= MAX_LENGTH) { "A name has at most $MAX_LENGTH characters" }
            return CatalogName(trimmed)
        }
    }
}

/** The internal number the business knows an item by: one to ten digits, kept as text. */
@JvmInline
value class ItemNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is one to ten digits. */
        fun of(value: String): ItemNumber {
            require(FORMAT.matches(value)) { "Not an item number: '$value'" }
            return ItemNumber(value)
        }
    }
}

/** What an item is called on shelf tags and reports: not blank, at most 100 characters. */
@JvmInline
value class ItemDescription private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100

        /** @throws IllegalArgumentException if [value] is blank or longer than [MAX_LENGTH] once trimmed. */
        fun of(value: String): ItemDescription {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "An item description cannot be blank" }
            require(trimmed.length <= MAX_LENGTH) { "An item description has at most $MAX_LENGTH characters" }
            return ItemDescription(trimmed)
        }
    }
}
