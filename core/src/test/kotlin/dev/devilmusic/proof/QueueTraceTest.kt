package dev.devilmusic.proof

import kotlin.test.*

class QueueTraceTest {
    private val ids = listOf("content://private/secret-a.mp3", "content://private/secret-b.mp3")
    @Test fun distinguishesSelectionFromReorderingAcrossServiceControllerAndUi() {
        val trace = QueueTrace(ByteArray(32) { 1 })
        trace.record(QueueTrace.Scope.CONTROLLER, QueueTrace.Event.SELECT, ids, 0, ids[1], 1)
        trace.record(QueueTrace.Scope.SERVICE, QueueTrace.Event.TRANSITION, ids, 1)
        trace.record(QueueTrace.Scope.UI, QueueTrace.Event.RENDER, ids, 1)
        trace.record(QueueTrace.Scope.SERVICE, QueueTrace.Event.TIMELINE, ids.reversed(), 0)
        val order = Regex("order=([a-f0-9]+)").findAll(trace.report()).map { it.groupValues[1] }.toList()
        assertEquals(order[0], order[1]); assertEquals(order[1], order[2]); assertNotEquals(order[2], order[3])
        assertTrue(trace.report().contains("CONTROLLER/SELECT")); assertTrue(trace.report().contains("SERVICE/TRANSITION"))
    }
    @Test fun excludesOriginalPathsAndChangesTokensBetweenProcesses() {
        val first = QueueTrace(ByteArray(32) { 1 }); val second = QueueTrace(ByteArray(32) { 2 })
        for (trace in listOf(first, second)) trace.record(QueueTrace.Scope.UI, QueueTrace.Event.SELECT, ids, 0, ids[1], 1)
        assertFalse(first.report().contains("content://")); assertFalse(first.report().contains("secret-a")); assertFalse(first.report().contains("secret-b"))
        assertNotEquals(first.report(), second.report())
    }
    @Test fun boundsHistoryAndVisibleRowsWithoutDroppingOrderEvidence() {
        val trace = QueueTrace(); val many = (0..1000).map { "content://item/$it" }
        repeat(50) { trace.record(QueueTrace.Scope.UI, QueueTrace.Event.RENDER, many, it) }
        assertEquals(32, trace.report().lines().count { it.contains("UI/RENDER") })
        assertTrue(trace.report().length < 16000)
        assertFalse(trace.report().contains("9:"))
    }
}
