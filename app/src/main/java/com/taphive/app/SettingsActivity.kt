package com.taphive.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val app = application as TapHiveApp
        val prefs = app.prefs
        val enabled = findViewById<CheckBox>(R.id.syslog_enabled)
        val host = findViewById<EditText>(R.id.syslog_host)
        val port = findViewById<EditText>(R.id.syslog_port)
        val transport = findViewById<Spinner>(R.id.syslog_transport)
        val format = findViewById<Spinner>(R.id.syslog_format)
        val facility = findViewById<EditText>(R.id.syslog_facility)
        val appName = findViewById<EditText>(R.id.syslog_app_name)
        val startAtBoot = findViewById<CheckBox>(R.id.start_at_boot)
        val enrichment = findViewById<CheckBox>(R.id.enrichment_enabled)
        val retention = findViewById<Spinner>(R.id.retention)
        val error = findViewById<TextView>(R.id.error)

        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.info).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        transport.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf("udp", "tcp", "tls")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val formatValues = arrayOf("rfc3164", "rfc5424")
        format.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf("RFC 3164 (BSD)", "RFC 5424")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val retentionLabels = arrayOf("Off", "1 hour", "6 hours", "24 hours", "7 days")
        val retentionHours = intArrayOf(0, 1, 6, 24, 168)
        retention.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            retentionLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        enabled.isChecked = prefs.enabled
        host.setText(prefs.host)
        port.setText(prefs.port.toString())
        transport.setSelection(
            when (prefs.transport) {
                "tcp" -> 1
                "tls" -> 2
                else -> 0
            }
        )
        format.setSelection(formatValues.indexOf(prefs.syslogFormat).coerceAtLeast(0))
        facility.setText(prefs.facility.toString())
        appName.setText(prefs.appName)
        startAtBoot.isChecked = prefs.startAtBoot
        enrichment.isChecked = prefs.enrichmentEnabled
        retention.setSelection(
            retentionHours.indexOf(prefs.retentionHours).coerceAtLeast(0)
        )

        findViewById<Button>(R.id.clear_history).setOnClickListener {
            app.database.clear()
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val portValue = port.text.toString().toIntOrNull()
            val facilityValue = facility.text.toString().toIntOrNull()
            if (portValue == null || facilityValue == null) {
                error.text = "Port and facility must be numbers"
                return@setOnClickListener
            }
            if (enabled.isChecked) {
                val problem = prefs.validate(host.text.toString(), portValue, facilityValue)
                if (problem != null) {
                    error.text = problem
                    return@setOnClickListener
                }
            }
            val retentionIndex = retention.selectedItemPosition
                .coerceIn(0, retentionHours.lastIndex)
            val formatIndex = format.selectedItemPosition
                .coerceIn(0, formatValues.lastIndex)
            prefs.setStartAtBoot(startAtBoot.isChecked)
            prefs.setEnrichmentEnabled(enrichment.isChecked)
            prefs.saveRetention(retentionHours[retentionIndex])
            app.database.prune()
            prefs.save(
                enabled.isChecked,
                host.text.toString(),
                portValue,
                transport.selectedItem as String,
                facilityValue,
                appName.text.toString(),
                formatValues[formatIndex]
            )
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
