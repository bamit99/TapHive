package com.taphive.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrafficDatabaseTest {
    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private lateinit var database: TrafficDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
        prefs = Prefs(context)
        prefs.saveRetention(24)
        database = TrafficDatabase(context, prefs, TEST_DB)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DB)
        prefs.saveRetention(24)
    }

    @Test
    fun countsDistinctFlowsNotSampleRows() {
        val now = System.currentTimeMillis()
        database.recordFlow(now, "flow-1", 1000, "1.1.1.1", 443, 6, 100)
        database.recordFlow(now, "flow-1", 1000, "1.1.1.1", 443, 6, 200)
        database.recordFlow(now, "flow-2", 1000, "1.1.1.1", 443, 6, 300)

        val rows = database.appRows()
        assertEquals(1, rows.size)
        assertEquals(2L, rows[0].flows)
        assertEquals(600L, rows[0].bytes)
    }

    @Test
    fun retentionOffClearsFlowsAndMetadata() {
        prefs.saveRetention(24)
        database.recordFlow(
            System.currentTimeMillis(), "flow-1", 1000, "1.1.1.1", 443, 6, 100
        )
        database.upsertGeo("1.1.1.1", "United States", "Example Org")
        prefs.saveRetention(0)
        database.prune()

        assertEquals(0L, count("flows"))
        assertEquals(0L, count("ip_meta"))
    }

    private fun count(table: String): Long =
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    private companion object {
        const val TEST_DB = "taphive-test.db"
    }
}
