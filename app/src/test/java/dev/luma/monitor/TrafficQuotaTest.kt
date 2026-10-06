package dev.luma.monitor

import org.junit.Assert.*
import org.junit.Test

class TrafficQuotaTest {
    @Test fun usesServersBillingMethodForRemainingTraffic() {
        val expected = mapOf("sum" to 55.0, "max" to 28.0, "min" to 27.0, "up" to 27.0, "down" to 28.0, "unknown" to 28.0)
        expected.forEach { (type, used) ->
            val quota = TrafficQuota.calculate(1000.0, type, 27.0, 28.0)
            assertEquals(used, quota.used!!, 0.0)
            assertEquals(1000 - used, quota.remaining!!, 0.0)
            assertEquals(used / 10, quota.percent!!, 0.000001)
        }
    }

    @Test fun missingCountersRemainUnknownAndUnlimitedAndOverQuotaAreDistinct() {
        assertNull(TrafficQuota.calculate(1000.0, "sum", null, 28.0).remaining)
        assertNull(TrafficQuota.calculate(null, "max", 27.0, 28.0).remaining)
        assertEquals(973.0, TrafficQuota.calculate(1000.0, "up", 27.0, null).remaining!!, 0.0)
        assertTrue(TrafficQuota.calculate(0.0, "sum", 27.0, 28.0).unlimited)
        val exceeded = TrafficQuota.calculate(50.0, "sum", 27.0, 28.0)
        assertEquals(0.0, exceeded.remaining!!, 0.0)
        assertEquals(110.0, exceeded.percent!!, 0.000001)
    }
}
