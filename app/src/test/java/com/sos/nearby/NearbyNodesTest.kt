package com.sos.nearby

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NearbyNodesTest {
    @Test fun strongestAreChosenRatherThanFirstSeen() {
        val nodes = NearbyNodes()
        for (i in 0..39) nodes.observe("node-$i", -100 + i, 0)
        val selected = nodes.closest(1, 29)
        assertEquals(29, selected.size)
        assertEquals("node-39", selected.first())
        assertFalse("node-0" in selected)
        assertTrue("node-11" in selected)
    }
    @Test fun staleNodesAndInvalidSignalsAreExcluded() {
        val nodes = NearbyNodes()
        nodes.observe("gone", -10, 0)
        nodes.observe("here", -70, 29_999)
        nodes.observe("invalid", 127, 29_999)
        assertEquals(listOf("here"), nodes.closest(30_000))
    }
    @Test fun stableNodeIdIsCountedOnceAndStrengthIsSmoothed() {
        val nodes = NearbyNodes()
        nodes.observe("same-id", -80, 0)
        nodes.observe("same-id", -20, 1)
        nodes.observe("other", -50, 1)
        assertEquals(listOf("other", "same-id"), nodes.closest(2))
    }
    @Test fun branchGrantsPayForReceiversBeforeFurtherRelaying() {
        val plan = RecipientPlan(listOf("a", "b", "c"), 29)
        assertEquals(29, plan.recipients.sumOf { 1 + plan.grants.getValue(it) })
        assertTrue(plan.grants.values.all { it < 29 })
        val crowded = RecipientPlan((0..90).map { "node-$it" }, 29)
        assertEquals(29, crowded.recipients.size)
        assertTrue(crowded.grants.values.all { it == 0 })
        assertEquals(emptyList<String>(), RecipientPlan(emptyList(), 29).recipients)
    }
    @Test fun retriesCannotAddReplacementNodesOrNewBudget() {
        val plan = RecipientPlan((0..50).map { "node-$it" }, 29)
        val attempts = mutableMapOf<String, Int>()
        while (true) {
            val id = plan.next() ?: break
            plan.attempted(id); attempts[id] = (attempts[id] ?: 0) + 1
        }
        assertEquals(29, attempts.size)
        assertTrue(attempts.values.all { it == 2 })
        assertFalse("node-29" in attempts)
        assertEquals(29, plan.recipients.sumOf { 1 + plan.grants.getValue(it) })
    }
    @Test fun simulatedMultiHopTreesNeverExceedThirtyIncludingOrigin() {
        for (seed in 0..100) {
            val random = java.util.Random(seed.toLong())
            var visited = 1
            fun propagate(budget: Int, hop: Int) {
                if (hop > 3 || budget == 0) return
                val candidates = (0 until random.nextInt(45)).map { UUID.randomUUID().toString() }
                val plan = RecipientPlan(candidates, budget)
                for (id in plan.recipients) {
                    plan.attempted(id)
                    // Lost deliveries waste budget; allocations are never redistributed.
                    if (random.nextInt(4) == 0) continue
                    visited++
                    propagate(plan.grants.getValue(id), hop + 1)
                }
            }
            propagate(29, 0)
            assertTrue("seed=$seed reached $visited devices", visited <= 30)
        }
    }
    @Test fun aSparseFirstHopStillAllowsRelayingWithinTheTotalBudget() {
        val first = RecipientPlan(listOf("bridge"), 29)
        assertEquals(28, first.grants.getValue("bridge"))
        val second = RecipientPlan((0..99).map { "node-$it" }, first.grants.getValue("bridge"))
        assertEquals(30, 1 + first.recipients.size + second.recipients.size)
    }
}
