package com.daig0rian.mirakurun.tvinput

import android.app.Activity
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

private const val ACTION_SETUP_INPUTS = "android.media.tv.action.SETUP_INPUTS"

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.text_status)
        val watchButton = findViewById<Button>(R.id.button_watch)
        val setupButton = findViewById<Button>(R.id.button_setup)

        val channelCount = queryChannelCount()
        statusText.text = if (channelCount > 0) {
            "${channelCount} チャンネル登録済み"
        } else {
            "チャンネル未登録"
        }

        val tvIntent = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)
        if (channelCount > 0 && tvIntent.resolveActivity(packageManager) != null) {
            watchButton.visibility = View.VISIBLE
            watchButton.setOnClickListener { startActivity(tvIntent) }
        }

        setupButton.setOnClickListener {
            val setupIntent = Intent(ACTION_SETUP_INPUTS)
            if (setupIntent.resolveActivity(packageManager) != null) {
                startActivity(setupIntent)
            } else {
                Toast.makeText(this, "Mirakurun TV Input を利用するには TV アプリが必要です", Toast.LENGTH_LONG).show()
                val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tv"))
                startActivity(storeIntent)
            }
        }
    }

    private fun queryChannelCount(): Int {
        val inputId = resolveInputId() ?: return 0
        val cursor = contentResolver.query(
            TvContract.buildChannelsUriForInput(inputId),
            arrayOf(TvContract.Channels._ID),
            null, null, null,
        )
        return cursor?.use { it.count } ?: 0
    }

    private fun resolveInputId(): String? {
        val manager = getSystemService(TvInputManager::class.java) ?: return null
        return manager.tvInputList
            .firstOrNull { it.serviceInfo.packageName == packageName }
            ?.id
    }
}
