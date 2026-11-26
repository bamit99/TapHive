package com.taphive.app

import android.app.Application

class TapHiveApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var sender: SyslogSender
        private set
    lateinit var tracker: TrafficTracker
        private set
    lateinit var database: TrafficDatabase
        private set
    lateinit var geoResolver: IpGeoResolver
        private set
    lateinit var appNameResolver: AppNameResolver
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sender = SyslogSender(prefs)
        sender.start()
        database = TrafficDatabase(this, prefs)
        database.prune()
        tracker = TrafficTracker()
        geoResolver = IpGeoResolver(database)
        appNameResolver = AppNameResolver(this)

        // Capture resume is driven by MainActivity (explicit foreground entry) or
        // by the service's null-intent restart. Do not start from Application.onCreate:
        // doing so bypasses the startAtBoot opt-in on a boot-triggered process.
    }
}
