package com.music.bitchord.playback.preset

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoEqDatabaseTest {
    private lateinit var database: AutoEqDatabase

    @Before
    fun open() {
        database = AutoEqDatabase.open(RuntimeEnvironment.getApplication())
    }

    @After
    fun close() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `packaged schema counts and PAWS golden rows match sqlite3 inspection`() {
        assertEquals(17_703, database.count())
        assertEquals(8_853, database.count(parametric = false))
        assertEquals(8_850, database.count(parametric = true))

        val graphic = database.get(1)
        assertNotNull(graphic)
        graphic!!
        assertEquals("7Hz Salnotes Zero", graphic.name)
        assertEquals("Auriculares Argentina in-ear", graphic.source)
        assertFalse(graphic.parametric)
        assertEquals(0.0f, graphic.preamp)
        assertEquals(34, graphic.bands.size)
        assertEquals(32, graphic.regularBands.size)
        assertEquals(PawsBandType.BASS, graphic.bands[0].type)
        assertEquals(90, graphic.bands[0].frequencyHz)
        assertEquals(PawsBandType.TREBLE, graphic.bands[1].type)
        assertEquals(10_000, graphic.bands[1].frequencyHz)
        assertEquals(20, graphic.regularBands.first().frequencyHz)
        assertEquals(4.0f, graphic.regularBands.first().gain)
        assertEquals(PawsBandType.GRAPHIC, graphic.regularBands.first().type)
        assertEquals(19_871, graphic.regularBands.last().frequencyHz)
        assertEquals(-3.5f, graphic.regularBands.last().gain)

        val parametric = database.get(2)
        assertNotNull(parametric)
        parametric!!
        assertTrue(parametric.parametric)
        assertEquals(12, parametric.bands.size)
        assertEquals(10, parametric.regularBands.size)
        val first = parametric.regularBands.first()
        assertEquals(PawsBandType.LOW_SHELF, first.type)
        assertEquals(105, first.frequencyHz)
        assertEquals(2.6f, first.gain)
        assertEquals(0.7f, first.q)
        val second = parametric.regularBands[1]
        assertEquals(PawsBandType.PEAKING, second.type)
        assertEquals(6_337, second.frequencyHz)
        assertEquals(5.3f, second.gain)
        assertEquals(3.61f, second.q)
        assertEquals(0xFF113399.toInt(), second.color)
        assertEquals(PawsBandType.HIGH_SHELF, parametric.regularBands[5].type)
    }

    @Test
    fun `queries bind values filter type and escape LIKE metacharacters`() {
        val both = database.search("7Hz Salnotes Zero", limit = 50)
        assertEquals(24, both.size)
        assertEquals(listOf(1L, 2L), both.take(2).map { it.id })
        val parametric = database.search("7Hz Salnotes Zero", parametric = true, limit = 50)
        assertEquals(12, parametric.size)
        assertTrue(parametric.all { it.parametric && it.name.contains("7Hz Salnotes Zero") })
        val graphic = database.search("7Hz Salnotes Zero", parametric = false, limit = 50)
        assertEquals(12, graphic.size)
        assertTrue(graphic.all { !it.parametric })
        assertEquals(1L, database.search("7Hz Salnotes Zero", limit = 1).single().id)
        assertTrue(database.search("%").isEmpty())
        assertTrue(database.search("_").isEmpty())
        assertTrue(database.search("' OR 1=1 --").isEmpty())
        assertNull(database.get(Long.MAX_VALUE))
        try {
            database.list(limit = 0)
            fail("Expected limit validation")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `metadata listing reaches every packaged row`() {
        val all = database.list(limit = 20_000)
        assertEquals(17_703, all.size)
        assertEquals(17_703, all.map { it.id }.toSet().size)
        assertEquals(11_722L, all.first().id)
        assertEquals(8_850, database.list(parametric = true, limit = 9_000).size)
    }

    @Test
    fun `close is idempotent and later reads fail`() {
        database.close()
        database.close()
        try {
            database.count()
            fail("Expected closed database failure")
        } catch (_: AutoEqDatabaseException) {
        }
    }
}
