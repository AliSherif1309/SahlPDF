package com.gitlab.mudlej.MjPdfReader.ui.tools

/** Stable IDs distinguish duplicate copies of the same source page. */
data class PlannedPage(val id: Long, val sourceIndex: Int, val rotation: Int = 0)
data class PagePlan(val pages: List<PlannedPage>, val selected: Set<Long> = emptySet()) {
    init {
        require(pages.size <= PageSelection.MAX_OUTPUT_PAGES)
        require(pages.map { it.id }.distinct().size == pages.size)
        require(pages.all { it.sourceIndex >= 0 && it.rotation in listOf(0, 90, 180, 270) })
        require(selected.all { id -> pages.any { it.id == id } })
    }
    fun toggle(id: Long): PagePlan {
        require(pages.any { it.id == id })
        return copy(selected = if (id in selected) selected - id else selected + id)
    }
    fun selectAll(): PagePlan = copy(selected = pages.map { it.id }.toSet())
    fun clearSelection(): PagePlan = copy(selected = emptySet())
    fun rotate(): PagePlan = copy(pages = pages.map {
        if (it.id in selected) it.copy(rotation = (it.rotation + 90) % 360) else it
    })
    fun delete(): PagePlan {
        require(selected.size < pages.size) { "Keep at least one page" }
        return copy(pages = pages.filterNot { it.id in selected }, selected = emptySet())
    }
    fun duplicate(): PagePlan {
        require(pages.size + selected.size <= PageSelection.MAX_OUTPUT_PAGES)
        var nextId = (pages.maxOfOrNull { it.id } ?: -1L) + 1
        val copies = mutableSetOf<Long>()
        val result = pages.flatMap { page ->
            if (page.id in selected) {
                val duplicate = page.copy(id = nextId++)
                copies.add(duplicate.id)
                listOf(page, duplicate)
            } else listOf(page)
        }
        return PagePlan(result, copies)
    }
    /** Move each selected run one position; retain relative order and boundary pages. */
    fun move(delta: Int): PagePlan {
        require(delta == -1 || delta == 1)
        val result = pages.toMutableList()
        val indices = if (delta < 0) 1 until result.size else (result.size - 2) downTo 0
        for (i in indices) {
            val neighbor = i + delta
            if (result[i].id in selected && result[neighbor].id !in selected) {
                java.util.Collections.swap(result, i, neighbor)
            }
        }
        return copy(pages = result)
    }
    companion object {
        fun initial(count: Int): PagePlan {
            require(count in 1..PageSelection.MAX_OUTPUT_PAGES)
            return PagePlan(List(count) { PlannedPage(it.toLong(), it) })
        }
    }
}
