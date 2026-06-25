package com.daig0rian.mirakurun.tvinput

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class MirakurunApiClient(private val baseUrl: String) {

    private val httpClient = OkHttpClient()

    fun fetchServices(): List<MirakurunService> {
        val request = Request.Builder()
            .url("$baseUrl/api/services")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("レスポンスが空です")
            return parseServices(body)
        }
    }

    fun fetchLogo(serviceId: Long): Bitmap? {
        val request = Request.Builder()
            .url("$baseUrl/api/services/$serviceId/logo")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.d(TAG, "ロゴ取得失敗 (serviceId=$serviceId): $e")
            null
        }
    }

    fun fetchPrograms(): List<MirakurunProgram> {
        val request = Request.Builder()
            .url("$baseUrl/api/programs")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: return emptyList()
            return parsePrograms(body)
        }
    }

    private fun parseServices(json: String): List<MirakurunService> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            try {
                val obj = array.getJSONObject(i)
                val channel = obj.optJSONObject("channel") ?: return@mapNotNull null
                val name = obj.optString("name", "")
                    .takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                MirakurunService(
                    id = obj.getLong("id"),
                    serviceId = obj.getInt("serviceId"),
                    networkId = obj.getInt("networkId"),
                    name = name,
                    channelType = channel.optString("type", "OTHER"),
                    serviceType = obj.optInt("type", 1),
                    remoteControlKeyId = obj.optInt("remoteControlKeyId", 0),
                )
            } catch (e: Exception) {
                Log.w(TAG, "サービスのパースをスキップ (index=$i): $e")
                null
            }
        }
    }

    private fun parsePrograms(json: String): List<MirakurunProgram> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            try {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name", "")
                    .takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                MirakurunProgram(
                    id = obj.getLong("id"),
                    serviceId = obj.getInt("serviceId"),
                    networkId = obj.getInt("networkId"),
                    startAtMillis = obj.getLong("startAt"),
                    durationMillis = obj.getLong("duration"),
                    name = name,
                    description = obj.optString("description", null)
                        ?.takeIf { it != "null" },
                )
            } catch (e: Exception) {
                Log.w(TAG, "番組のパースをスキップ (index=$i): $e")
                null
            }
        }
    }

    companion object {
        private const val TAG = "MirakurunApiClient"
    }
}
