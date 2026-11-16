package com.gitlab.mudlej.MjPdfReader.ui.tools

import org.junit.Assert.*
import org.junit.Test

class PagePlanTest {
    @Test fun movingSelectedRunsKeepsRelativeOrderAndBoundaryPages() {
        val plan = PagePlan.initial(6).copy(selected = setOf(1L, 2L, 4L))
        assertEquals(listOf(1, 2, 0, 4, 3, 5), plan.move(-1).pages.map { it.sourceIndex })
        assertEquals(listOf(0, 3, 1, 2, 5, 4), plan.move(1).pages.map { it.sourceIndex })
        assertEquals(listOf(0, 1, 2), PagePlan.initial(3).selectAll().move(-1).pages.map { it.sourceIndex })
    }
    @Test fun duplicatingUsesUniqueIdsAndSelectsOnlyCopies() {
        val plan = PagePlan.initial(3).toggle(0).toggle(2).rotate().duplicate()
        assertEquals(listOf(0, 0, 1, 2, 2), plan.pages.map { it.sourceIndex })
        assertEquals(listOf(90, 90, 0, 90, 90), plan.pages.map { it.rotation })
        assertEquals(5, plan.pages.map { it.id }.distinct().size)
        assertEquals(setOf(plan.pages[1].id, plan.pages[4].id), plan.selected)
        assertEquals(listOf(0, 1, 2), plan.delete().pages.map { it.sourceIndex })
    }
    @Test fun deleteCannotRemoveEveryPage() {
        assertThrows(IllegalArgumentException::class.java) { PagePlan.initial(1).selectAll().delete() }
        assertThrows(IllegalArgumentException::class.java) { PagePlan.initial(3).selectAll().delete() }
    }
    @Test fun fourQuarterTurnsRestoreOrientationWithoutMutatingSourcePlan() {
        val original = PagePlan.initial(2).toggle(1)
        var rotated = original
        repeat(4) { rotated = rotated.rotate() }
        assertEquals(original, rotated)
        assertEquals(listOf(0, 0), original.pages.map { it.rotation })
    }
    @Test fun expansionIsBoundedAndDeletedIdsDoNotCollideWithRemainingPages() {
        assertThrows(IllegalArgumentException::class.java) { PagePlan.initial(1000).toggle(0).duplicate() }
        val plan = PagePlan.initial(3).toggle(1).delete().toggle(2).duplicate()
        assertEquals(plan.pages.size, plan.pages.map { it.id }.distinct().size)
    }
    @Test fun boundariesAndInvalidPlansAreRejected() {
        assertEquals(listOf(0), PagePlan.initial(1).toggle(0).move(1).pages.map { it.sourceIndex })
        assertThrows(IllegalArgumentException::class.java) { PagePlan.initial(0) }
        assertThrows(IllegalArgumentException::class.java) { PagePlan(listOf(PlannedPage(0, -1))) }
        assertThrows(IllegalArgumentException::class.java) { PagePlan(listOf(PlannedPage(0, 0, 45))) }
        assertThrows(IllegalArgumentException::class.java) { PagePlan.initial(1).toggle(99) }
    }
}
