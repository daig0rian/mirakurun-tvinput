package com.daig0rian.mirakurun.tvinput

import android.media.tv.TvInputService
import android.util.Log
import androidx.media3.common.util.UnstableApi

private const val TAG = "MirakurunTvInputSvc"

@UnstableApi
class MirakurunTvInputService : TvInputService() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    override fun onCreateSession(inputId: String): Session {
        Log.d(TAG, "onCreateSession: $inputId")
        return MirakurunSession(this, inputId)
    }
}
