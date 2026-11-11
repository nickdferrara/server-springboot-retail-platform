package com.retailplatform.shared

/** Which slice of a list a query asks for. Pages are numbered from zero. */
@ConsistentCopyVisibility
data class PageQuery private constructor(
    val page: Int,
    val size: Int,
) {
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 200

        /** @throws IllegalArgumentException if [page] is negative or [size] is not between 1 and [MAX_SIZE]. */
        fun of(
            page: Int = 0,
            size: Int = DEFAULT_SIZE,
        ): PageQuery {
            require(page >= 0) { "A page number cannot be negative: $page" }
            require(size in 1..MAX_SIZE) { "A page holds between 1 and $MAX_SIZE items: $size" }
            return PageQuery(page, size)
        }
    }
}

/** One slice of a list, with the size of the whole list. */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), page, size, total)
}
