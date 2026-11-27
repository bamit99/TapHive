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
        database.recordFlow(now, "flow-1", 1000, "1.1.1.1", 443, 6, 60, 40)
        database.recordFlow(now, "flow-1", 1000, "1.1.1.1", 443, 6, 120, 80)
        database.recordFlow(now, "flow-2", 1000, "1.1.1.1", 443, 6, 180, 120)

        val rows = database.appRows()
        assertEquals(1, rows.size)
        assertEquals(2L, rows[0].flows)
        assertEquals(600L, rows[0].bytes)
    }

    @Test
    fun appDetailIncludesDirectionAndProtocols() {
        val now = System.currentTimeMillis()
        database.recordFlow(now, "flow-1", 1000, "1.1.1.1", 443, 6, 60, 40)
        database.recordFlow(now, "flow-1", 1000, "1.1.1.1", 443, 6, 120, 80)
        database.recordFlow(now, "flow-2", 1000, "1.1.1.1", 53, 17, 10, 5)

        val detail = database.appDetail(1000)!!
        assertEquals(315L, detail.bytes)
        assertEquals(190L, detail.bytesSent)
        assertEquals(125L, detail.bytesReceived)
        assertEquals(2L, detail.flows)
        assertEquals(2, detail.protocols.size)
        assertEquals(2, detail.destinations.size)
    }

    @Test
    fun unresolvedIpsDropsAfterCountryLookup() {
        database.recordFlow(
            System.currentTimeMillis(), "flow-1", 1000, "116.119.113.161", 443, 17, 60, 40
        )
        assertEquals(listOf("116.119.113.161"), database.unresolvedIps(10))

        database.upsertGeo("116.119.113.161", "India", "Bharti Airtel")
        assertEquals(emptyList<String>(), database.unresolvedIps(10))
    }

    @Test
    fun retentionOffClearsFlowsAndMetadata() {
        prefs.saveRetention(24)
        database.recordFlow(
            System.currentTimeMillis(), "flow-1", 1000, "1.1.1.1", 443, 6, 60, 40
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
