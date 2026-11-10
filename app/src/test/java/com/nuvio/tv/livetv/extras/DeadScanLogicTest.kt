package com.nuvio.tv.livetv.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadScanLogicTest {

    @Test fun `verdict from status codes`() {
        assertEquals(ProbeVerdict.DEAD, DeadScanLogic.verdict(null))
        assertEquals(ProbeVerdict.ALIVE, DeadScanLogic.verdict(200))
        assertEquals(ProbeVerdict.ALIVE, DeadScanLogic.verdict(206))
        assertEquals(ProbeVerdict.ALIVE, DeadScanLogic.verdict(302))
        assertEquals(ProbeVerdict.DEAD, DeadScanLogic.verdict(404))
        assertEquals(ProbeVerdict.DEAD, DeadScanLogic.verdict(410))
        assertEquals(ProbeVerdict.DEAD, DeadScanLogic.verdict(500))
        // Connection / login limits: can't tell.
        listOf(401, 403, 429, 458, 503, 509).forEach { assertEquals(ProbeVerdict.UNKNOWN, DeadScanLogic.verdict(it)) }
        assertEquals(ProbeVerdict.UNKNOWN, DeadScanLogic.verdict(418))
    }

    @Test fun `only http streams are probed`() {
        assertTrue(DeadScanLogic.probeable("http://a/b.ts"))
        assertTrue(DeadScanLogic.probeable("HTTPS://a/b.m3u8"))
        assertFalse(DeadScanLogic.probeable("stalker://portal/ch1"))
        assertFalse(DeadScanLogic.probeable(""))
    }

    @Test fun `merge replaces known answers and keeps old on unknown`() {
        val old = mapOf(
            "a" to DeadScanEntry(true, 10),
            "b" to DeadScanEntry(false, 10),
            "c" to DeadScanEntry(true, 10),
            "gone" to DeadScanEntry(true, 10)
        )
        val found = mapOf("a" to ProbeVerdict.ALIVE, "b" to ProbeVerdict.DEAD, "c" to ProbeVerdict.UNKNOWN, "d" to ProbeVerdict.DEAD, "e" to ProbeVerdict.UNKNOWN)
        val out = DeadScanLogic.merge(old, found, 99, setOf("a", "b", "c", "d", "e"))
        assertEquals(DeadScanEntry(false, 99), out["a"])
        assertEquals(DeadScanEntry(true, 99), out["b"])
        assertEquals(DeadScanEntry(true, 10), out["c"])
        assertEquals(DeadScanEntry(true, 99), out["d"])
        assertFalse(out.containsKey("e"))
        assertFalse(out.containsKey("gone"))
    }

    @Test fun `cancelled scan keeps unscanned old results`() {
        val old = mapOf("a" to DeadScanEntry(true, 10), "b" to DeadScanEntry(true, 10))
        val out = DeadScanLogic.merge(old, mapOf("a" to ProbeVerdict.ALIVE), 50, setOf("a", "b"))
        assertEquals(setOf("b"), DeadScanLogic.deadKeys(out))
    }

    @Test fun `hide only when switched on`() {
        val list = listOf("a", "b", "c")
        assertEquals(list, DeadScanLogic.hide(list, setOf("b"), false) { it })
        assertEquals(listOf("a", "c"), DeadScanLogic.hide(list, setOf("b"), true) { it })
        assertEquals(list, DeadScanLogic.hide(list, emptySet(), true) { it })
    }
}
