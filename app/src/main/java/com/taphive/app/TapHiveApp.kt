package com.taphive.app

import android.app.Application

class TapHiveApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var sender: SyslogSender
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sender = SyslogSender(prefs)
        sender.start()
    }
}
