package dev.luma.monitor

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PingParserTest {
    @Test fun taskSelectionPersistsAndDefaultsToThreeWithoutReorderingByOldPrimary() {
        val tasks = (1..5).map { TaskChoice(it, "线路 $it") }
        val config = CardConfig("a", "Node", 5, "线路 5")
        assertEquals(listOf(1, 2, 3), config.selectedTasks(tasks).map { it.id })
        val selected = config.copy(taskIds = listOf(2, 4, 5))
        val restored = CardConfig.fromJson(selected.toJson())
        assertEquals(listOf(2, 4, 5), restored.selectedTasks(tasks).map { it.id })
        assertEquals(listOf(2, 5), restored.selectedTasks(tasks.filter { it.id != 4 }).map { it.id })
        assertEquals(1, CardConfig.fromJson(config.copy(hours = 24).toJson()).hours)
    }
    @Test fun windowStatisticsAndTimeoutAreKeptSeparate() {
        val result = PingParser.metricStats(JSONObject("""{"stats":[
          {"entity_id":"a","task_id":"7","total":120,"loss":10,"latest":-1,"avg":25.4,"min":20,"max":80}
        ]}"""), "a", 7)!!
        assertNull(result.latency)
        assertTrue(result.timedOut)
        assertEquals(25.4, result.average!!, 0.0)
        assertEquals(20.0, result.minimum!!, 0.0)
        assertEquals(80.0, result.maximum!!, 0.0)
    }

    @Test fun richSnapshotsKeepEveryTaskAndResourceAcrossCacheRoundTrip() {
        val snapshot = CardSnapshot.demo()
        assertEquals(snapshot, CardSnapshot.fromJson(snapshot.toJson()))
        assertEquals("—", DisplayValues.speed(null))
        assertEquals("1.0 KB/s", DisplayValues.speed(1024.0))
        assertEquals("10 天 0 小时", DisplayValues.uptime(864000))
    }
    @Test fun weightsLossByRawSamplesAndKeepsLatestTimeout() {
        val payload = JSONObject("""{"records":[
          {"task_id":7,"client":"a","time":1,"value":20,"count":59,"loss":0},
          {"task_id":7,"client":"a","time":2,"value":-1,"count":1,"loss":100}
        ]}""")
        val result = PingParser.legacy(payload, "a", 7)
        assertEquals(100.0 / 60.0, result.loss!!, 0.0001)
        assertEquals(60L, result.samples)
        assertNull(result.latency)
    }

    @Test fun missingRecordsDoNotBecomeZeroLoss() {
        val result = PingParser.legacy(JSONObject("""{"records":[],"tasks":[{"id":7,"loss":0,"total":0}]}"""), "a", 7)
        assertNull(result.loss)
        assertNull(result.latency)
        assertEquals(0L, result.samples)
    }

    @Test fun filtersOtherNodesAndTasks() {
        val result = PingParser.legacy(JSONObject("""{"records":[
          {"task_id":7,"client":"b","time":3,"value":-1},
          {"task_id":8,"client":"a","time":4,"value":-1},
          {"task_id":7,"client":"a","time":2,"value":12.5}
        ]}"""), "a", 7)
        assertEquals(0.0, result.loss!!, 0.0)
        assertEquals(12.5, result.latency!!, 0.0)
        assertEquals(1L, result.samples)
    }

    @Test fun modernStatsUseServerWindowAndExactAssignment() {
        val result = PingParser.metricStats(JSONObject("""{"stats":[
          {"entity_id":"b","task_id":7,"total":100,"loss":90,"latest":500},
          {"entity_id":"a","task_id":7,"total":60,"loss":5,"latest":25.4}
        ]}"""), "a", 7)!!
        assertEquals(5.0, result.loss!!, 0.0)
        assertEquals(25.4, result.latency!!, 0.0)
        assertEquals(60L, result.samples)
    }

    @Test fun zeroSampleStatsAndInvalidPercentagesRemainUnknown() {
        val empty = PingParser.metricStats(JSONObject("""{"stats":[{"entity_id":"a","task_id":7,"total":0,"loss":0,"latest":0}]}"""), "a", 7)!!
        assertNull(empty.loss); assertNull(empty.latency)
        val invalid = PingParser.metricStats(JSONObject("""{"stats":[{"entity_id":"a","task_id":7,"total":5,"loss":-1,"latest":-1}]}"""), "a", 7)!!
        assertNull(invalid.loss); assertNull(invalid.latency)
    }

    @Test fun fullLossDoesNotDisplayOldSuccessfulLatency() {
        val result = PingParser.legacy(JSONObject("""{"tasks":[{"id":7,"total":10,"loss":100,"latest":30}]}"""), "a", 7)
        assertNull(result.latency)
        assertEquals(100.0, result.loss!!, 0.0)
    }

    @Test fun latestTimeoutIsNotOverwrittenByOldTaskSuccess() {
        val result = PingParser.legacy(JSONObject("""{"records":[
          {"task_id":7,"client":"a","time":2,"value":-1},
          {"task_id":7,"client":"a","time":1,"value":30}
        ],"tasks":[{"id":7,"total":2,"loss":50,"latest":30}]}"""), "a", 7)
        assertNull(result.latency)
        assertEquals(50.0, result.loss!!, 0.0)
    }
}
