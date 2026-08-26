package com.rallycopilot.core

import com.rallycopilot.core.knowledge.KnowledgeMath
import com.rallycopilot.core.knowledge.RoadBucket
import com.rallycopilot.core.knowledge.SlowdownMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeMathTests {
    @Test
    fun `confirmed hazard pins factor low and warrants caution`() {
        var b = RoadBucket(1, 4)
        b = KnowledgeMath.applySlowEvent(b, observedRatio = 0.5, confirmed = true)
        assertTrue(b.hazardConfirmed)
        assertTrue(KnowledgeMath.warrantsCaution(b))
        assertEquals(0.7, b.speedFactor, 0.01)
    }

    @Test
    fun `three soft events infer a hazard without confirmation`() {
        var b = RoadBucket(1, 4)
        repeat(3) { b = KnowledgeMath.applySlowEvent(b, 0.5, confirmed = false) }
        assertFalse(b.hazardConfirmed)
        assertTrue(KnowledgeMath.warrantsCaution(b))
        assertTrue(b.speedFactor < 0.85)
    }

    @Test
    fun `one soft event alone does not warrant caution`() {
        val b = KnowledgeMath.applySlowEvent(RoadBucket(1, 4), 0.5, confirmed = false)
        assertFalse(KnowledgeMath.warrantsCaution(b))
    }

    @Test
    fun `clean passes relax the factor and eventually clear confirmation`() {
        var b = KnowledgeMath.applySlowEvent(RoadBucket(1, 4), 0.5, confirmed = true)
        val pinned = b.speedFactor
        repeat(3) { b = KnowledgeMath.applyCleanPass(b) }
        assertEquals(pinned, b.speedFactor, 0.001) // confirmed: holds for first passes
        repeat(6) { b = KnowledgeMath.applyCleanPass(b) }
        assertTrue(b.speedFactor > pinned)         // then relaxes
        assertFalse(b.hazardConfirmed)             // and clears after 8 clean passes
    }

    @Test
    fun `rough surface warrants caution`() {
        var b = RoadBucket(1, 4)
        repeat(5) { b = KnowledgeMath.addRoughness(b, 4.0) }
        assertTrue(KnowledgeMath.warrantsCaution(b))
    }
}

class SlowdownMonitorTests {
    @Test
    fun `sustained unexplained slowdown fires an anomaly`() {
        val m = SlowdownMonitor()
        var out: SlowdownMonitor.Anomaly? = null
        for (t in 0..3000L step 200) {
            out = m.tick(t, edgeId = 7, offsetM = 300.0, speedMps = 8.0, expectedMps = 24.0, nearMappedHazardM = null)
            if (out != null) break
        }
        assertNotNull(out)
        assertTrue(out!!.observedRatio < 0.55)
    }

    @Test
    fun `slowdown near a mapped junction is explained and ignored`() {
        val m = SlowdownMonitor()
        for (t in 0..5000L step 200) {
            assertNull(m.tick(t, 7, 300.0, 8.0, 24.0, nearMappedHazardM = 40.0))
        }
    }

    @Test
    fun `normal driving never fires`() {
        val m = SlowdownMonitor()
        for (t in 0..5000L step 200) {
            assertNull(m.tick(t, 7, 300.0, 22.0, 24.0, null))
        }
    }

    @Test
    fun `same bucket prompts only once per drive`() {
        val m = SlowdownMonitor()
        var count = 0
        for (t in 0..120_000L step 200) {
            if (m.tick(t, 7, 300.0, 8.0, 24.0, null) != null) count++
        }
        assertEquals(1, count)
    }

    @Test
    fun `bump within six seconds is attached to the anomaly`() {
        val m = SlowdownMonitor()
        m.reportBump(500)
        var out: SlowdownMonitor.Anomaly? = null
        for (t in 0..4000L step 200) {
            out = m.tick(t, 7, 300.0, 8.0, 24.0, null)
            if (out != null) break
        }
        assertTrue(out!!.hadBump)
    }
}
