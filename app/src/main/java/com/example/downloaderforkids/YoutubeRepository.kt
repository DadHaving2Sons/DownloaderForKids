package com.example.downloaderforkids

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class LibraryUpdateResult {
    object Updated : LibraryUpdateResult()
    object UpToDate : LibraryUpdateResult()
    data class Failed(val error: Throwable) : LibraryUpdateResult()
}

class YoutubeRepository {

    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(url)
                val info = YoutubeDL.getInstance().getInfo(request)
                Result.success(info)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun updateLibrary(context: Context): LibraryUpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                when (YoutubeDL.getInstance().updateYoutubeDL(context)) {
                    UpdateStatus.DONE -> LibraryUpdateResult.Updated
                    UpdateStatus.ALREADY_UP_TO_DATE,
                    null -> LibraryUpdateResult.UpToDate
                }
            } catch (e: Exception) {
                LibraryUpdateResult.Failed(e)
            }
        }
    }
}
