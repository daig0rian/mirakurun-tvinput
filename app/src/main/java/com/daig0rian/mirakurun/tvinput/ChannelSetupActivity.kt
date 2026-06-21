package com.daig0rian.mirakurun.tvinput

import android.app.Activity
import android.media.tv.TvInputInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ChannelSetupActivity"

class ChannelSetupActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val inputId = intent.getStringExtra(TvInputInfo.EXTRA_INPUT_ID)
        if (inputId == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val urlInputLayout = findViewById<LinearLayout>(R.id.layout_url_input)
        val urlEdit = findViewById<EditText>(R.id.edit_url)
        val startButton = findViewById<Button>(R.id.button_start)
        val statusText = findViewById<TextView>(R.id.text_status)
        val closeButton = findViewById<Button>(R.id.button_close)

        val savedUrl = MirakurunPreferences.getUrl(this)
        urlEdit.setText(savedUrl)

        if (MirakurunPreferences.isUrlConfigured(this)) {
            startButton.requestFocus()
        } else {
            urlEdit.requestFocus()
        }

        closeButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        startButton.setOnClickListener {
            val url = urlEdit.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            MirakurunPreferences.setUrl(this, url)

            urlInputLayout.visibility = View.GONE
            statusText.visibility = View.VISIBLE
            startSync(inputId, statusText, closeButton)
        }
    }

    private fun startSync(inputId: String, statusText: TextView, closeButton: Button) {
        scope.launch {
            try {
                statusText.text = "Mirakurun からチャンネルを取得中..."

                val apiClient = MirakurunApiClient(MirakurunPreferences.getUrl(this@ChannelSetupActivity))

                val services = withContext(Dispatchers.IO) {
                    apiClient.fetchServices()
                }

                statusText.text = "${services.size} サービスを取得しました。チャンネル登録中..."

                val count = withContext(Dispatchers.IO) {
                    ChannelManager(this@ChannelSetupActivity).syncChannels(inputId, services)
                }

                statusText.text = "${count} チャンネルを登録しました。番組表を取得中..."

                withContext(Dispatchers.IO) {
                    EpgSyncManager(this@ChannelSetupActivity).syncEpg(
                        inputId = inputId,
                        apiClient = apiClient,
                        onProgress = { current, total ->
                            launch(Dispatchers.Main) {
                                statusText.text = "番組表を取得中... ($current/$total)"
                            }
                        },
                    )
                }

                EpgSyncWorker.schedule(this@ChannelSetupActivity)

                statusText.text = "${count} チャンネルと番組表の登録が完了しました。"
                Log.i(TAG, "セットアップ完了: $count チャンネル")
                delay(1500)
                setResult(RESULT_OK)
                finish()

            } catch (e: Exception) {
                Log.e(TAG, "セットアップエラー: $e")
                statusText.text = "エラー: ${e.message}"
                closeButton.visibility = View.VISIBLE
                closeButton.requestFocus()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
