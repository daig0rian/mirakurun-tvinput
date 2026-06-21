package com.daig0rian.mirakurun.tvinput

import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.util.Log

class EpgSyncManager(private val context: Context) {

    fun syncEpg(
        inputId: String,
        apiClient: MirakurunApiClient,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val channelMap = buildChannelMap(inputId)
        if (channelMap.isEmpty()) {
            Log.w(TAG, "登録済みチャンネルが見つかりません。チャンネル登録を先に行ってください。")
            return
        }

        Log.i(TAG, "番組情報を取得中...")
        val allPrograms = apiClient.fetchPrograms()
        Log.i(TAG, "番組数: ${allPrograms.size}")

        // (serviceId, networkId) でグループ化
        val programsByService = allPrograms.groupBy { Pair(it.serviceId, it.networkId) }

        val total = channelMap.size
        channelMap.entries.forEachIndexed { index, (key, channelId) ->
            onProgress(index + 1, total)
            val programs = programsByService[key] ?: emptyList()
            if (programs.isNotEmpty()) {
                upsertPrograms(channelId, programs)
            }
        }

        Log.i(TAG, "EPG 同期完了")
    }

    private fun buildChannelMap(inputId: String): Map<Pair<Int, Int>, Long> {
        val map = mutableMapOf<Pair<Int, Int>, Long>()
        val cursor = context.contentResolver.query(
            TvContract.buildChannelsUriForInput(inputId),
            arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_SERVICE_ID,
                TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
            ),
            null, null, null,
        ) ?: return map

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(TvContract.Channels._ID)
            val sidIdx = it.getColumnIndexOrThrow(TvContract.Channels.COLUMN_SERVICE_ID)
            val nidIdx = it.getColumnIndexOrThrow(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID)
            while (it.moveToNext()) {
                map[Pair(it.getInt(sidIdx), it.getInt(nidIdx))] = it.getLong(idIdx)
            }
        }
        return map
    }

    private fun upsertPrograms(channelId: Long, programs: List<MirakurunProgram>) {
        context.contentResolver.delete(
            TvContract.buildProgramsUriForChannel(channelId),
            null, null,
        )

        val values = programs.map { program ->
            ContentValues().apply {
                put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId)
                put(TvContract.Programs.COLUMN_TITLE, program.name)
                put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, program.startAtMillis)
                put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, program.startAtMillis + program.durationMillis)
                program.description?.let { put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, it) }
            }
        }.toTypedArray()

        if (values.isNotEmpty()) {
            context.contentResolver.bulkInsert(TvContract.Programs.CONTENT_URI, values)
        }
    }

    companion object {
        private const val TAG = "EpgSyncManager"
    }
}
