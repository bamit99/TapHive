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
    private val prefs: Prefs,
    dbName: String = DB_NAME
) : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {

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

    data class IpMeta(
        val ip: String,
        val country: String?,
        val region: String?,
        val city: String?,
        val asn: Int?,
        val org: String?,
        val isp: String?,
        val provider: String?,
        val dnsName: String?,
        val updatedAt: Long
    )

    data class ProtocolRow(
        val protocol: Int,
        val bytes: Long,
        val bytesSent: Long,
        val bytesReceived: Long,
        val flows: Long
    )

    data class PortRow(
        val port: Int,
        val protocol: Int,
        val bytes: Long,
        val flows: Long
    )

    data class AppDetail(
        val uid: Int,
        val bytes: Long,
        val bytesSent: Long,
        val bytesReceived: Long,
        val flows: Long,
        val protocols: List<ProtocolRow>,
        val destinations: List<DestinationRow>,
        val ports: List<PortRow>
    )

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE flows (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "flow_id TEXT NOT NULL DEFAULT ''," +
                "uid INTEGER NOT NULL," +
                "ip TEXT NOT NULL," +
                "port INTEGER NOT NULL," +
                "protocol INTEGER NOT NULL," +
                "bytes INTEGER NOT NULL," +
                "bytes_sent INTEGER NOT NULL DEFAULT 0," +
                "bytes_received INTEGER NOT NULL DEFAULT 0)"
        )
        createIndexes(db)
        db.execSQL(
            "CREATE TABLE ip_meta (" +
                "ip TEXT PRIMARY KEY," +
                "country TEXT," +
                "region TEXT," +
                "city TEXT," +
                "asn INTEGER," +
                "org TEXT," +
                "isp TEXT," +
                "provider TEXT," +
                "dns_name TEXT," +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ip_meta_updated ON ip_meta(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE flows ADD COLUMN flow_id TEXT NOT NULL DEFAULT ''")
            createIndexes(db)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ip_meta_updated ON ip_meta(updated_at)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE ip_meta ADD COLUMN region TEXT")
            db.execSQL("ALTER TABLE ip_meta ADD COLUMN city TEXT")
            db.execSQL("ALTER TABLE ip_meta ADD COLUMN asn INTEGER")
            db.execSQL("ALTER TABLE ip_meta ADD COLUMN isp TEXT")
            db.execSQL("ALTER TABLE ip_meta ADD COLUMN provider TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE flows ADD COLUMN bytes_sent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE flows ADD COLUMN bytes_received INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_flows_ts ON flows(ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_flows_ip ON flows(ip)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_flows_uid ON flows(uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_flows_flow_id ON flows(flow_id)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_flows_destination " +
                "ON flows(ip, port, protocol, uid)"
        )
    }

    fun recordFlow(
        ts: Long,
        flowId: String,
        uid: Int,
        ip: String,
        port: Int,
        protocol: Int,
        bytesSent: Long,
        bytesReceived: Long
    ) {
        val total = bytesSent + bytesReceived
        if (total <= 0L || prefs.retentionHours <= 0) return
        writableDatabase.insert(
            "flows",
            null,
            ContentValues().apply {
                put("ts", ts)
                put("flow_id", flowId)
                put("uid", uid)
                put("ip", ip)
                put("port", port)
                put("protocol", protocol)
                put("bytes", total)
                put("bytes_sent", bytesSent)
                put("bytes_received", bytesReceived)
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
            db.execSQL("DELETE FROM ip_meta")
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
        upsertGeo(ip, country, null, null, null, org, null, null)
    }

    fun upsertGeo(
        ip: String,
        country: String?,
        region: String?,
        city: String?,
        asn: Int?,
        org: String?,
        isp: String?,
        provider: String?
    ) {
        if (prefs.retentionHours <= 0) return
        writableDatabase.execSQL(
            "INSERT INTO ip_meta(ip, country, region, city, asn, org, isp, provider, updated_at) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(ip) DO UPDATE SET " +
                "country = COALESCE(excluded.country, ip_meta.country), " +
                "region = COALESCE(excluded.region, ip_meta.region), " +
                "city = COALESCE(excluded.city, ip_meta.city), " +
                "asn = COALESCE(excluded.asn, ip_meta.asn), " +
                "org = COALESCE(excluded.org, ip_meta.org), " +
                "isp = COALESCE(excluded.isp, ip_meta.isp), " +
                "provider = COALESCE(excluded.provider, ip_meta.provider), " +
                "updated_at = excluded.updated_at",
            arrayOf<Any?>(
                ip, country, region, city, asn, org, isp, provider,
                System.currentTimeMillis()
            )
        )
    }

    fun ipMeta(ip: String): IpMeta? {
        readableDatabase.rawQuery(
            "SELECT ip, country, region, city, asn, org, isp, provider, dns_name, updated_at " +
                "FROM ip_meta WHERE ip = ? LIMIT 1",
            arrayOf(ip)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return IpMeta(
                ip = cursor.getString(0),
                country = cursor.getString(1),
                region = cursor.getString(2),
                city = cursor.getString(3),
                asn = if (cursor.isNull(4)) null else cursor.getInt(4),
                org = cursor.getString(5),
                isp = cursor.getString(6),
                provider = cursor.getString(7),
                dnsName = cursor.getString(8),
                updatedAt = cursor.getLong(9)
            )
        }
    }

    fun upsertDnsName(ip: String, name: String) {
        if (name.isBlank() || prefs.retentionHours <= 0) return
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
            "SELECT uid, SUM(bytes) AS total, COUNT(DISTINCT flow_id) AS cnt FROM flows " +
                "GROUP BY uid HAVING total > 0 ORDER BY total DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(AppRow(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2)))
            }
        }
        return rows
    }

    fun appDetail(uid: Int, limit: Int = 10): AppDetail? {
        val capped = limit.coerceIn(1, 50)
        var total = 0L
        var sent = 0L
        var received = 0L
        var flows = 0L
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(bytes), 0), COALESCE(SUM(bytes_sent), 0), " +
                "COALESCE(SUM(bytes_received), 0), COUNT(DISTINCT flow_id) " +
                "FROM flows WHERE uid = ?",
            arrayOf(uid.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                total = cursor.getLong(0)
                sent = cursor.getLong(1)
                received = cursor.getLong(2)
                flows = cursor.getLong(3)
            }
        }
        if (total <= 0L) return null

        val protocols = ArrayList<ProtocolRow>()
        readableDatabase.rawQuery(
            "SELECT protocol, SUM(bytes), COALESCE(SUM(bytes_sent), 0), " +
                "COALESCE(SUM(bytes_received), 0), COUNT(DISTINCT flow_id) " +
                "FROM flows WHERE uid = ? GROUP BY protocol ORDER BY SUM(bytes) DESC",
            arrayOf(uid.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                protocols.add(
                    ProtocolRow(
                        protocol = cursor.getInt(0),
                        bytes = cursor.getLong(1),
                        bytesSent = cursor.getLong(2),
                        bytesReceived = cursor.getLong(3),
                        flows = cursor.getLong(4)
                    )
                )
            }
        }

        val destinations = ArrayList<DestinationRow>()
        readableDatabase.rawQuery(
            "SELECT f.ip, f.port, f.protocol, f.uid, SUM(f.bytes) AS total, " +
                "COUNT(DISTINCT f.flow_id) AS cnt, m.country, m.org, m.dns_name " +
                "FROM flows f LEFT JOIN ip_meta m ON m.ip = f.ip " +
                "WHERE f.uid = ? GROUP BY f.ip, f.port, f.protocol, f.uid " +
                "ORDER BY total DESC LIMIT ?",
            arrayOf(uid.toString(), capped.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                destinations.add(
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

        val ports = ArrayList<PortRow>()
        readableDatabase.rawQuery(
            "SELECT port, protocol, SUM(bytes), COUNT(DISTINCT flow_id) " +
                "FROM flows WHERE uid = ? GROUP BY port, protocol " +
                "ORDER BY SUM(bytes) DESC LIMIT ?",
            arrayOf(uid.toString(), capped.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                ports.add(
                    PortRow(
                        port = cursor.getInt(0),
                        protocol = cursor.getInt(1),
                        bytes = cursor.getLong(2),
                        flows = cursor.getLong(3)
                    )
                )
            }
        }

        return AppDetail(uid, total, sent, received, flows, protocols, destinations, ports)
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

    /** Public/local destination IPs with no country metadata yet. */
    fun unresolvedIps(limit: Int): List<String> {
        val rows = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT f.ip, SUM(f.bytes) AS total FROM flows f " +
                "LEFT JOIN ip_meta m ON m.ip = f.ip " +
                "WHERE m.country IS NULL OR m.country = '' " +
                "GROUP BY f.ip ORDER BY total DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.getString(0))
        }
        return rows
    }

    fun topDestinations(limit: Int): List<DestinationRow> {
        val rows = ArrayList<DestinationRow>()
        readableDatabase.rawQuery(
            "SELECT f.ip, f.port, f.protocol, f.uid, SUM(f.bytes) AS total, " +
                "COUNT(DISTINCT f.flow_id) AS cnt, m.country, m.org, m.dns_name " +
                "FROM flows f LEFT JOIN ip_meta m ON m.ip = f.ip " +
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
        const val DB_VERSION = 4
        const val PRUNE_INTERVAL_MS = 60_000L
        const val META_TTL_MS = 30L * 24 * 3_600_000L
    }
}
