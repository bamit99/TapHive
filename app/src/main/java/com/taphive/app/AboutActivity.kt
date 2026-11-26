package com.taphive.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<Button>(R.id.back).setOnClickListener { finish() }

        val emailIntent = Intent(Intent.ACTION_SENDTO)
            .setData(Uri.parse("mailto:${getString(R.string.about_email)}"))
        listOf(R.id.email_row, R.id.email_icon).forEach { id ->
            findViewById<View>(id).setOnClickListener {
                openExternally(emailIntent, getString(R.string.email_chooser))
            }
        }

        val linkedInIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.linkedin.com/in/amitxbhatnagar/")
        )
        listOf(R.id.linkedin_row, R.id.linkedin_icon).forEach { id ->
            findViewById<View>(id).setOnClickListener {
                openExternally(linkedInIntent, "Open LinkedIn")
            }
        }

        findViewById<Button>(R.id.license).setOnClickListener { showLicense() }
    }

    private fun openExternally(intent: Intent, chooser: String) {
        try {
            startActivity(Intent.createChooser(intent, chooser))
        } catch (_: ActivityNotFoundException) {
            // No matching app installed; leave the current screen intact.
        }
    }

    private fun showLicense() {
        val body = assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
        val text = TextView(this).apply {
            this.text = body
            this.textSize = 12f
            setTextColor(Color.parseColor("#E6F4FF"))
            setPadding(48, 24, 48, 24)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(R.string.license_title)
            .setView(scroll)
            .setPositiveButton(R.string.license_close, null)
            .show()
    }
}
