package com.daig0rian.mirakurun.tvinput

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val TAG = "MirakurunSession"
private const val TRACK_ID_MAIN     = "audio_main"
private const val TRACK_ID_SUB      = "audio_sub"
private const val TRACK_ID_SUBTITLE = "subtitle_jpn"

@UnstableApi
class MirakurunSession(
    private val context: Context,
    @Suppress("unused") private val inputId: String,
) : TvInputService.Session(context) {

    private var player: ExoPlayer? = null
    private var currentSurface: Surface? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var released = false
    // onTune() が連続呼び出しされると、古い tune-worker が遅れて startPlayback() を呼ぶ。
    // tuneToken を毎回インクリメントし、古いトークンを持つ worker は無視する。
    @Volatile private var tuneToken = 0

    // onTracksChanged で収集した音声グループ。デュアルモノ時は [主音声, 副音声] の順で格納。
    // ExoPlayer と TIF のコールバックは両方メインスレッドのため排他不要。
    private val audioGroups = mutableListOf<Tracks.Group>()

    // ARIB字幕
    private val overlayView = SubtitleOverlayView(context).also { it.visibility = View.VISIBLE }
    private var captionHandle: Long = 0
    private var captionEnabled = true

    init {
        setOverlayViewEnabled(true)
    }

    override fun onRelease() {
        Log.d(TAG, "onRelease")
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        destroyCaptionSession()
        player?.release()
        player = null
    }

    override fun onSetSurface(surface: Surface?): Boolean {
        Log.d(TAG, "onSetSurface: $surface")
        currentSurface = surface
        player?.setVideoSurface(surface)
        return true
    }

    override fun onSetStreamVolume(volume: Float) {
        player?.volume = volume
    }

    override fun onCreateOverlayView(): View {
        Log.d(TAG, "onCreateOverlayView called")
        return overlayView
    }

    override fun onSetCaptionEnabled(enabled: Boolean) {
        Log.d(TAG, "onSetCaptionEnabled: $enabled (ignored, use onSelectTrack)")
    }

    override fun onTune(channelUri: Uri): Boolean {
        Log.d(TAG, "onTune: $channelUri")
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)

        // onTune() は TIF メインスレッドで 2000ms 以内に返す必要がある。
        // ContentResolver クエリと OkHttpClient 初期化（SSL コンテキスト等）が遅いため
        // バックグラウンドスレッドで実行し、ExoPlayer 作成だけをメインスレッドに post する。
        //
        // Sony Bravia 等は短時間に onTune() を複数回呼ぶことがある。各呼び出しで
        // ExoPlayer を作り直すと Mirakurun のチューナーが即時再起動され、次の接続が
        // 数十秒待たされる。tuneToken で古い worker の startPlayback 呼び出しをキャンセルする。
        val token = ++tuneToken

        Thread {
            val serviceId = resolveServiceId(channelUri)
            Log.d(TAG, "resolvedServiceId: $serviceId (token=$token)")
            if (serviceId == null || released || token != tuneToken) return@Thread

            val baseUrl = MirakurunPreferences.getUrl(context)
            val streamUrl = "$baseUrl/api/services/$serviceId/stream"
            val httpClient = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val dataSourceFactory = TsReadexDataSource.Factory(
                OkHttpDataSource.Factory(httpClient)
            )

            // 字幕 PES をメインスレッドでデコード→バッファ遅延分だけ表示を遅らせる。
            // 字幕 PES は TsReadexDataSource.read() でデータ到着時に抽出されるが、
            // 映像・音声は ExoPlayer のバッファを経由するため、その差分を補正する。
            dataSourceFactory.captionPesListener = CaptionPesListener { ptsMs, pesPayload ->
                mainHandler.post {
                    val h = captionHandle
                    if (h == 0L || !captionEnabled) return@post
                    if (AribCaptionFilter.decode(h, ptsMs, pesPayload, 0, pesPayload.size)) {
                        val p = player
                        val delayMs = if (p != null) {
                            (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
                        } else 0L
                        if (delayMs > 50) {
                            mainHandler.postDelayed({ scheduleRender(ptsMs) }, delayMs)
                        } else {
                            scheduleRender(ptsMs)
                        }
                    }
                }
            }

            if (released || token != tuneToken) return@Thread
            mainHandler.post { if (!released && token == tuneToken) startPlayback(streamUrl, dataSourceFactory) }
        }.also { it.name = "tune-worker-$token" }.start()

        return true
    }

    override fun onSelectTrack(type: Int, trackId: String?): Boolean {
        if (type == TvTrackInfo.TYPE_SUBTITLE) {
            val enabled = trackId != null
            Log.d(TAG, "onSelectTrack: subtitle enabled=$enabled")
            captionEnabled = enabled
            overlayView.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
            if (!enabled) overlayView.clearCaptions()
            notifyTrackSelected(type, trackId)
            return true
        }
        if (type != TvTrackInfo.TYPE_AUDIO) return false
        val exoPlayer = player ?: return false

        val groupIndex = when (trackId) {
            TRACK_ID_MAIN -> 0
            TRACK_ID_SUB  -> 1
            else -> return false
        }
        if (groupIndex >= audioGroups.size) return false

        val targetGroup = audioGroups[groupIndex].mediaTrackGroup
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(targetGroup, listOf(0)))
            .build()

        Log.d(TAG, "onSelectTrack: $trackId → audioGroups[$groupIndex]")
        notifyTrackSelected(type, trackId)
        return true
    }

    private fun resolveServiceId(channelUri: Uri): String? {
        val cursor = context.contentResolver.query(
            channelUri,
            arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null,
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun startPlayback(url: String, dataSourceFactory: TsReadexDataSource.Factory) {
        Log.d(TAG, "startPlayback: $url (surface=$currentSurface)")
        player?.release()

        // チャンネル切り替え時に前回の音声・字幕トラック情報をクリアする
        audioGroups.clear()
        notifyTracksChanged(emptyList())
        mainHandler.removeCallbacksAndMessages(null)
        overlayView.clearCaptions()
        destroyCaptionSession()

        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))

        // ライブ MPEG-TS 用バッファ設定。
        // デフォルトの bufferForPlaybackAfterRebufferMs=5000ms は初期閾値 2500ms より大きく、
        // 放送レート配信では STATE_READY 直後に BUFFERING へ逆戻りしてフレームが止まる。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 1_000,
                /* maxBufferMs */ 8_000,
                /* bufferForPlaybackMs */ 500,
                /* bufferForPlaybackAfterRebufferMs */ 1_000,
            )
            .build()

        // 字幕デコーダーセッションを起動（デフォルト解像度 1920x1080; onVideoSizeChanged で更新）
        captionHandle = AribCaptionFilter.create(1920, 1080)
        Log.d(TAG, "captionHandle=$captionHandle")

        player = ExoPlayer.Builder(context, DefaultRenderersFactory(context))
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer ->
                exoPlayer.setVideoSurface(currentSurface)
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "onPlaybackStateChanged: $playbackState")
                        if (playbackState == Player.STATE_READY) {
                            Log.d(TAG, "STATE_READY → notifyVideoAvailable")
                            notifyVideoAvailable()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "onPlayerError: $error")
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        val newAudioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        Log.d(TAG, "onTracksChanged: ${newAudioGroups.size} audio group(s)")
                        audioGroups.clear()
                        audioGroups.addAll(newAudioGroups)
                        notifyTifTracks()
                    }
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val h = captionHandle
                            if (h != 0L) AribCaptionFilter.setFrameSize(h, videoSize.width, videoSize.height)
                            overlayView.setVideoSize(videoSize.width, videoSize.height)
                            Log.d(TAG, "videoSize=${videoSize.width}x${videoSize.height}")
                        }
                    }
                })
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
    }

    // Called from mainHandler (main thread). May be invoked immediately or via postDelayed
    // to compensate for ExoPlayer's buffer latency.
    private fun scheduleRender(ptsMs: Long) {
        val h = captionHandle
        if (h == 0L || !captionEnabled) return
        Log.d(TAG, "scheduleRender: calling render pts=$ptsMs")
        val images = AribCaptionFilter.render(h, ptsMs)
        Log.d(TAG, "scheduleRender: got ${images.size} image(s)")
        if (images.isNotEmpty()) {
            overlayView.showCaptions(images)
            Log.d(TAG, "scheduleRender: showCaptions called, overlayVisibility=${overlayView.visibility} w=${overlayView.width} h=${overlayView.height}")
        }
    }

    private fun destroyCaptionSession() {
        val h = captionHandle
        captionHandle = 0L
        if (h != 0L) AribCaptionFilter.destroy(h)
    }

    private fun notifyTifTracks() {
        val tracks = mutableListOf<TvTrackInfo>()

        if (audioGroups.size >= 2) {
            tracks.add(TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, TRACK_ID_MAIN)
                .setAudioChannelCount(1).build())
            tracks.add(TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, TRACK_ID_SUB)
                .setAudioChannelCount(1).build())
        }

        tracks.add(TvTrackInfo.Builder(TvTrackInfo.TYPE_SUBTITLE, TRACK_ID_SUBTITLE)
            .setLanguage("jpn").build())

        notifyTracksChanged(tracks)

        if (audioGroups.size >= 2) {
            notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, TRACK_ID_MAIN)
        }
        if (captionEnabled) {
            notifyTrackSelected(TvTrackInfo.TYPE_SUBTITLE, TRACK_ID_SUBTITLE)
        }
        Log.d(TAG, "notifyTifTracks: ${tracks.size} track(s), captionEnabled=$captionEnabled")
    }
}
