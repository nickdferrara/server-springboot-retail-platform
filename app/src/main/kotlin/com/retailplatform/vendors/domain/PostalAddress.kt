package com.retailplatform.vendors.domain

/** A mailing address, such as where a vendor's payments are sent. */
@ConsistentCopyVisibility
data class PostalAddress private constructor(
    val line1: String,
    val line2: String?,
    val city: String,
    val region: String,
    val postalCode: String,
    val countryCode: String,
) {
    companion object {
        private const val MAX_LINE = 100
        private const val MAX_POSTAL_CODE = 12
        private val COUNTRY = Regex("[A-Z]{2}")

        /**
         * @param countryCode ISO 3166-1 alpha-2, for example `US`.
         * @throws IllegalArgumentException if a required part is blank, a part is too long, or the country code is
         * not two upper-case letters.
         */
        fun of(
            line1: String,
            line2: String?,
            city: String,
            region: String,
            postalCode: String,
            countryCode: String,
        ): PostalAddress {
            require(COUNTRY.matches(countryCode)) { "Not an ISO country code: '$countryCode'" }
            return PostalAddress(
                line1 = part("Address line 1", line1, MAX_LINE),
                line2 = line2?.trim()?.takeIf { it.isNotEmpty() }?.let { part("Address line 2", it, MAX_LINE) },
                city = part("City", city, MAX_LINE),
                region = part("Region", region, MAX_LINE),
                postalCode = part("Postal code", postalCode, MAX_POSTAL_CODE),
                countryCode = countryCode,
            )
        }

        private fun part(
            label: String,
            value: String,
            maxLength: Int,
        ): String {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "$label cannot be blank" }
            require(trimmed.length <= maxLength) { "$label has at most $maxLength characters" }
            return trimmed
        }
    }
}
