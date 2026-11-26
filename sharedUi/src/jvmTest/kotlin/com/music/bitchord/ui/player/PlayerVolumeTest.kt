package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class PlayerVolumeTest {
    private class RecordingVolume(initial: Float = 0f) : SystemVolume {
        private val mutableLevel = MutableStateFlow(initial)
        override val level: StateFlow<Float> = mutableLevel.asStateFlow()
        val writes = mutableListOf<Float>()

        override fun set(level: Float) {
            writes += level
            mutableLevel.value = level
        }

        override fun refresh() = Unit
    }

    private fun withVolume(
        block: suspend CoroutineScope.(PlayerVolume, RecordingVolume) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val system = RecordingVolume()
        val volume = PlayerVolume(system, scope)
        try {
            block(volume, system)
        } finally {
            volume.close()
            scope.cancel()
        }
    }

    @Test
    fun aBurstAndReleaseBeforeTheWorkerRunsWriteOnlyTheExactFinalLevel() = withVolume { volume, system ->
        repeat(100) { volume.drag((it + 1) / 100f) }
        volume.release()
        assertEquals(listOf(1f), system.writes, "release itself flushes synchronously")
        yield()

        assertEquals(1f, volume.level.value)
        assertEquals(listOf(1f), system.writes)
        assertFalse(volume.dragging)
    }

    @Test
    fun aBurstInOneWindowWritesOnlyItsLatestIntermediateLevel() = withVolume { volume, system ->
        volume.drag(0.01f)
        yield()
        repeat(99) { volume.drag((it + 2) / 100f) }
        yield()

        assertEquals(1f, volume.level.value)
        assertTrue(system.writes.isEmpty())
        delay(80)
        assertEquals(listOf(1f), system.writes)
    }

    @Test
    fun releaseFlushesAThrottledValueAndLeavesNoStaleTrailingWrite() = withVolume { volume, system ->
        volume.drag(0.1f)
        yield()
        volume.drag(0.9f)
        yield()

        assertEquals(0.9f, volume.level.value)
        assertTrue(system.writes.isEmpty())

        volume.release()
        yield()
        assertEquals(listOf(0.9f), system.writes)

        delay(80)
        assertEquals(listOf(0.9f), system.writes)
        assertEquals(0.9f, volume.level.value)
    }

    @Test
    fun aHeldDragStillGetsItsTrailingPlatformWrite() = withVolume { volume, system ->
        volume.drag(0.4f)
        yield()
        assertTrue(system.writes.isEmpty())

        delay(80)
        assertEquals(listOf(0.4f), system.writes)
        assertEquals(0.4f, volume.level.value)

        volume.release()
        yield()
        assertEquals(0.4f, system.writes.last(), "release must flush even when the value was already written")
    }
}
