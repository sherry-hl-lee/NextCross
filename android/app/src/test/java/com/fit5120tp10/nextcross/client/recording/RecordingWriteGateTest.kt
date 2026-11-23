package com.fit5120tp10.nextcross.client.recording

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class RecordingWriteGateTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    @Test fun stopsTrackerThenDrainsAcceptedWritesBeforeCompletion() {
        val queue = Queue()
        val gate = RecordingWriteGate(queue)
        val log = mutableListOf<String>()
        gate.begin("one") { log += "insert" }
        gate.attach("one", { log += "start" }, { log += "stop" })
        gate.submit("one") { log += "gps" }
        gate.finish("one") { log += "complete" }
        assertFalse(gate.submit("one") { fail("Late sample accepted") })
        assertFalse(gate.attach("one", { fail("Late tracker started") }, {}))
        assertEquals(listOf("start", "stop"), log)
        queue.drain()
        assertEquals(listOf("start", "stop", "insert", "gps", "complete"), log)
    }
    @Test fun failedWritePreventsCompletion() {
        val queue = Queue()
        val gate = RecordingWriteGate(queue)
        gate.begin("one") {}
        gate.submit("one") { error("disk failure") }
        gate.finish("one") { fail("Failed trip must remain incomplete") }
        queue.tasks.removeFirst().run()
        try { queue.tasks.removeFirst().run(); fail() } catch (_: IllegalStateException) { }
        queue.drain()
    }
    @Test fun oldCallbacksCannotWriteIntoNewTrip() {
        val queue = Queue()
        val gate = RecordingWriteGate(queue)
        gate.begin("one") {}
        gate.finish("one") {}
        gate.begin("two") {}
        assertFalse(gate.submit("one") { fail() })
        assertTrue(gate.submit("two") {})
        gate.finish("two") {}
        queue.drain()
    }
}
