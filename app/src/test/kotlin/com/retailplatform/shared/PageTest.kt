package com.retailplatform.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageTest {
    @Test
    fun `defaults to the first page of fifty`() {
        PageQuery.of() shouldBe PageQuery.of(page = 0, size = 50)
        PageQuery.of(page = 3, size = 20).offset shouldBe 60L
    }

    @Test
    fun `rejects a negative page or a size outside 1 to 200`() {
        shouldThrow<IllegalArgumentException> { PageQuery.of(page = -1) }
        shouldThrow<IllegalArgumentException> { PageQuery.of(size = 0) }
        shouldThrow<IllegalArgumentException> { PageQuery.of(size = 201) }
    }

    @Test
    fun `a page maps its items and keeps its position`() {
        Page(listOf(1, 2), page = 1, size = 2, total = 5).map { it * 10 } shouldBe
            Page(listOf(10, 20), page = 1, size = 2, total = 5)
    }
}
