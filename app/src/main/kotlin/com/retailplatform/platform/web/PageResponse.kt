package com.retailplatform.platform.web

import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery

/** The JSON shape of a paged list. */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    companion object {
        fun <T, R> from(
            page: Page<T>,
            transform: (T) -> R,
        ): PageResponse<R> = PageResponse(page.items.map(transform), page.page, page.size, page.total)
    }
}

/** Builds a [PageQuery] from `page` and `size` request parameters; a bad value is reported as a 400. */
fun pageQuery(
    page: Int?,
    size: Int?,
): PageQuery = mapRequest { PageQuery.of(page ?: 0, size ?: PageQuery.DEFAULT_SIZE) }
