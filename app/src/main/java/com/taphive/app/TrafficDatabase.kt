package com.taphive.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local history of closed flows, independent of syslog forwarding.
 *
 * Writes come from the packet thread and reads from the UI thread;
 * SQLiteOpenHelper serializes access internally. Enrichment (country, org, DNS
 * name) is kept per IP so it can arrive after a flow has closed and still show
 * up in historical rows.
 */
class TrafficDatabase(
    context: Context,
    private val prefs: Prefs
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class DestinationRow(
        val ip: String,
        val port: Int,
        val protocol: Int,
        val uid: Int,
        val bytes: Long,
        val flows: Long,
        val country: String?,
        val org: String?,
        val dnsName: String?
    )

    data class AppRow(
        val uid: Int,
        val bytes: Long,
        val flows: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE flows (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "uid INTEGER NOT NULL," +
                "ip TEXT NOT NULL," +
                "port INTEGER NOT NULL," +
                "protocol INTEGER NOT NULL," +
                "bytes INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_flows_ts ON flows(ts)")
        db.execSQL("CREATE INDEX idx_flows_ip ON flows(ip)")
        db.execSQL(
            "CREATE TABLE ip_meta (" +
                "ip TEXT PRIMARY KEY," +
                "country TEXT," +
                "org TEXT," +
                "dns_name TEXT," +
                "updated_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS flows")
        db.execSQL("DROP TABLE IF EXISTS ip_meta")
        onCreate(db)
    }

    fun recordFlow(ts: Long, uid: Int, ip: String, port: Int, protocol: Int, bytes: Long) {
        if (bytes <= 0L || prefs.retentionHours <= 0) return
        writableDatabase.insert(
            "flows",
            null,
            ContentValues().apply {
                put("ts", ts)
                put("uid", uid)
                put("ip", ip)
                put("port", port)
                put("protocol", protocol)
                put("bytes", bytes)
            }
        )
        if (System.currentTimeMillis() - lastPruneMs >= PRUNE_INTERVAL_MS) prune()
    }

    @Volatile
    private var lastPruneMs = 0L

    /** Drops flows outside the configured window; called on writes, on save and on startup. */
    fun prune() {
        lastPruneMs = System.currentTimeMillis()
        val db = writableDatabase
        val hours = prefs.retentionHours
        if (hours <= 0) {
            db.execSQL("DELETE FROM flows")
        } else {
            val cutoff = System.currentTimeMillis() - hours * 3_600_000L
            db.delete("flows", "ts < ?", arrayOf(cutoff.toString()))
        }
        // Enrichment is a cache with its own, longer lifetime.
        db.delete(
            "ip_meta",
            "updated_at < ?",
            arrayOf((System.currentTimeMillis() - META_TTL_MS).toString())
        )
    }

    fun clear() {
        val db = writableDatabase
        db.execSQL("DELETE FROM flows")
        db.execSQL("DELETE FROM ip_meta")
        lastPruneMs = 0L
    }

    fun upsertGeo(ip: String, country: String?, org: String?) {
        writableDatabase.execSQL(
            "INSERT INTO ip_meta(ip, country, org, updated_at) VALUES(?, ?, ?, ?) " +
                "ON CONFLICT(ip) DO UPDATE SET " +
                "country = COALESCE(excluded.country, ip_meta.country), " +
                "org = COALESCE(excluded.org, ip_meta.org), " +
                "updated_at = excluded.updated_at",
            arrayOf<Any?>(ip, country, org, System.currentTimeMillis())
        )
    }

    fun upsertDnsName(ip: String, name: String) {
        if (name.isBlank()) return
        writableDatabase.execSQL(
            "INSERT INTO ip_meta(ip, dns_name, updated_at) VALUES(?, ?, ?) " +
                "ON CONFLICT(ip) DO UPDATE SET " +
                "dns_name = COALESCE(ip_meta.dns_name, excluded.dns_name), " +
                "updated_at = excluded.updated_at",
            arrayOf<Any?>(ip, name.trim(), System.currentTimeMillis())
        )
    }

    /** uid -> total bytes and flow count, largest first. */
    fun appRows(): List<AppRow> {
        val rows = ArrayList<AppRow>()
        readableDatabase.rawQuery(
            "SELECT uid, SUM(bytes) AS total, COUNT(*) AS cnt FROM flows " +
                "GROUP BY uid HAVING total > 0 ORDER BY total DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(AppRow(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2)))
            }
        }
        return rows
    }

    /** ip -> total bytes, largest first. */
    fun ipShares(): List<Pair<String, Long>> {
        val rows = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery(
            "SELECT ip, SUM(bytes) AS total FROM flows GROUP BY ip HAVING total > 0 ORDER BY total DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.getString(0) to cursor.getLong(1))
        }
        return rows
    }

    /** Country label -> total bytes, largest first. */
    fun countryShares(): List<Pair<String, Long>> {
        val rows = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery(
            "SELECT COALESCE(m.country, 'UNRESOLVED') AS label, SUM(f.bytes) AS total " +
                "FROM flows f LEFT JOIN ip_meta m ON m.ip = f.ip " +
                "GROUP BY label ORDER BY total DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.getString(0) to cursor.getLong(1))
        }
        return rows
    }

    fun topDestinations(limit: Int): List<DestinationRow> {
        val rows = ArrayList<DestinationRow>()
        readableDatabase.rawQuery(
            "SELECT f.ip, f.port, f.protocol, f.uid, SUM(f.bytes) AS total, COUNT(*) AS cnt, " +
                "m.country, m.org, m.dns_name FROM flows f LEFT JOIN ip_meta m ON m.ip = f.ip " +
                "GROUP BY f.ip, f.port, f.protocol, f.uid ORDER BY total DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    DestinationRow(
                        ip = cursor.getString(0),
                        port = cursor.getInt(1),
                        protocol = cursor.getInt(2),
                        uid = cursor.getInt(3),
                        bytes = cursor.getLong(4),
                        flows = cursor.getLong(5),
                        country = cursor.getString(6),
                        org = cursor.getString(7),
                        dnsName = cursor.getString(8)
                    )
                )
            }
        }
        return rows
    }

    private companion object {
        const val DB_NAME = "taphive_history.db"
        const val DB_VERSION = 1
        const val PRUNE_INTERVAL_MS = 60_000L
        const val META_TTL_MS = 30L * 24 * 3_600_000L
    }
}
