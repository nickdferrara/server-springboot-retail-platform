package com.retailplatform.vendors.domain

/** A person or desk at a vendor. */
@ConsistentCopyVisibility
data class VendorContact private constructor(
    val name: String,
    val email: String?,
    val phone: String?,
) {
    companion object {
        private const val MAX_LENGTH = 100
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+")

        /** @throws IllegalArgumentException if [name] is blank, a part is too long, or [email] is not an address. */
        fun of(
            name: String,
            email: String?,
            phone: String?,
        ): VendorContact {
            val cleanName = name.trim()
            val cleanEmail = email?.trim()?.takeIf { it.isNotEmpty() }
            val cleanPhone = phone?.trim()?.takeIf { it.isNotEmpty() }
            require(cleanName.isNotEmpty()) { "A contact name cannot be blank" }
            require(listOfNotNull(cleanName, cleanEmail, cleanPhone).all { it.length <= MAX_LENGTH }) {
                "Contact details have at most $MAX_LENGTH characters each"
            }
            require(cleanEmail == null || EMAIL.matches(cleanEmail)) { "Not an email address: '$cleanEmail'" }
            return VendorContact(cleanName, cleanEmail, cleanPhone)
        }
    }
}
