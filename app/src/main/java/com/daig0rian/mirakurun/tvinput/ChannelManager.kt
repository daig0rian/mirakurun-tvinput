package com.daig0rian.mirakurun.tvinput

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log

class ChannelManager(private val context: Context) {

    fun syncChannels(
        inputId: String,
        services: List<MirakurunService>,
        apiClient: MirakurunApiClient? = null,
    ): Int {
        // serviceType == 1 (デジタルTV) のみ対象。ラジオ(2)・データ放送(0xA0等)は除外。
        val tvServices = services.filter { it.serviceType == 1 }

        // 既存チャンネルを全削除してから再登録（差分管理は MVP 以降）
        context.contentResolver.delete(
            TvContract.buildChannelsUriForInput(inputId),
            null, null,
        )

        var count = 0
        for (service in tvServices) {
            val baseValues = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, service.name)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, service.displayNumber())
                put(TvContract.Channels.COLUMN_SERVICE_ID, service.serviceId)
                put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, service.networkId)
                put(TvContract.Channels.COLUMN_TYPE, service.channelType.toTifChannelType())
                put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, service.id.toString())
            }

            // システム起動の ChannelSetupActivity 経由では COLUMN_BROWSABLE を INSERT で設定できる。
            // MainActivity など非システム起動時は SecurityException になるので、その場合は
            // COLUMN_BROWSABLE を除いて INSERT し、後から UPDATE を試みる。
            val channelUri = try {
                val valuesWithBrowsable = ContentValues(baseValues).apply {
                    put(TvContract.Channels.COLUMN_BROWSABLE, 1)
                }
                context.contentResolver.insert(TvContract.Channels.CONTENT_URI, valuesWithBrowsable)
            } catch (e: SecurityException) {
                Log.d(TAG, "INSERT with COLUMN_BROWSABLE 拒否、フォールバック: ${e.message}")
                context.contentResolver.insert(TvContract.Channels.CONTENT_URI, baseValues)
            }

            if (channelUri != null) {
                // INSERT で COLUMN_BROWSABLE が設定できなかった場合、UPDATE でも試みる
                try {
                    context.contentResolver.update(
                        channelUri,
                        ContentValues().apply { put(TvContract.Channels.COLUMN_BROWSABLE, 1) },
                        null, null,
                    )
                } catch (_: SecurityException) {
                    // OEM によりシステムが制御。正規セットアップフロー経由で browsable になる
                }
                if (apiClient != null) {
                    storeChannelLogo(channelUri, service.id, apiClient)
                }
                count++
            }
        }
        return count
    }

    private fun storeChannelLogo(channelUri: Uri, serviceId: Long, apiClient: MirakurunApiClient) {
        val bitmap = apiClient.fetchLogo(serviceId) ?: return
        val logoUri = TvContract.buildChannelLogoUri(channelUri)
        try {
            context.contentResolver.openOutputStream(logoUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ロゴ保存失敗 (serviceId=$serviceId): $e")
        }
    }

    private fun String.toTifChannelType(): String = when (this) {
        "GR" -> TvContract.Channels.TYPE_DVB_T
        "BS" -> TvContract.Channels.TYPE_DVB_S
        "CS" -> TvContract.Channels.TYPE_DVB_S2
        else -> TvContract.Channels.TYPE_OTHER
    }

    companion object {
        private const val TAG = "ChannelManager"
    }
}
