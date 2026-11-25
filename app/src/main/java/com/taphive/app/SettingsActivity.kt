package com.taphive.app

import android.app.Activity
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

        val prefs = (application as TapHiveApp).prefs
        val enabled = findViewById<CheckBox>(R.id.syslog_enabled)
        val host = findViewById<EditText>(R.id.syslog_host)
        val port = findViewById<EditText>(R.id.syslog_port)
        val transport = findViewById<Spinner>(R.id.syslog_transport)
        val facility = findViewById<EditText>(R.id.syslog_facility)
        val appName = findViewById<EditText>(R.id.syslog_app_name)
        val error = findViewById<TextView>(R.id.error)

        transport.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf("udp", "tcp")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        enabled.isChecked = prefs.enabled
        host.setText(prefs.host)
        port.setText(prefs.port.toString())
        transport.setSelection(if (prefs.transport == "tcp") 1 else 0)
        facility.setText(prefs.facility.toString())
        appName.setText(prefs.appName)

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
            prefs.save(
                enabled.isChecked,
                host.text.toString(),
                portValue,
                transport.selectedItem as String,
                facilityValue,
                appName.text.toString()
            )
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
