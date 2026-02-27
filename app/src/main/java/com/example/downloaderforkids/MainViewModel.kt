package com.example.downloaderforkids

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val videos: List<VideoFormat>, val audios: List<VideoFormat>) : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val UPDATE_PREFS = "yt_dlp_update_prefs"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }
    
    private val repository = YoutubeRepository()
    private val updatePrefs by lazy {
        getApplication<Application>().getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
    }
    
    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    fun onToastShown() {
        _toastMessage.value = null
    }

    fun fetchFormats(url: String) {
        if (url.isBlank()) return
        
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.fetchVideoInfo(url)
            result.onSuccess { info ->
                val formats = info.formats
                if (formats == null) {
                    _uiState.value = UiState.Error("포맷 정보를 찾을 수 없습니다.")
                    return@onSuccess
                }

                val videoList = formats.filter {
                    it.vcodec != "none" && (it.acodec == "none" || it.acodec == null)
                }.sortedByDescending { it.height }

                val audioList = formats.filter {
                    it.acodec != "none" && (it.vcodec == "none" || it.vcodec == null)
                }.sortedByDescending { it.fileSize }
                
                _uiState.value = UiState.Success(videoList, audioList)
            }.onFailure {
                _uiState.value = UiState.Error("분석 실패: ${it.message}")
            }
        }
    }

    private val _isUpdating = MutableLiveData<Boolean>(false)
    val isUpdating: LiveData<Boolean> = _isUpdating
    private var pendingLibraryUpdateCheck = false

    init {
        requestLibraryUpdateCheck()
    }

    private fun requestLibraryUpdateCheck() {
        if (_isUpdating.value == true) return

        if (!isLibraryUpdateCheckDue()) {
            pendingLibraryUpdateCheck = false
            return
        }

        if (DownloadService.isDownloadInProgress()) {
            pendingLibraryUpdateCheck = true
            _toastMessage.value = "다운로드 중이라 yt-dlp 업데이트 확인을 건너뜁니다."
            return
        }

        pendingLibraryUpdateCheck = false
        markLibraryUpdateCheckAttempted()
        updateLibrary()
    }

    private fun isLibraryUpdateCheckDue(): Boolean {
        val lastCheckedAt = updatePrefs.getLong(KEY_LAST_CHECK_AT, 0L)
        return System.currentTimeMillis() - lastCheckedAt >= UPDATE_CHECK_INTERVAL_MS
    }

    private fun markLibraryUpdateCheckAttempted() {
        updatePrefs.edit()
            .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .apply()
    }

    private fun updateLibrary() {
        _isUpdating.value = true
        _toastMessage.value = "라이브러리 업데이트 확인 중..."
        viewModelScope.launch {
            when (val result = repository.updateLibrary(getApplication())) {
                is LibraryUpdateResult.Updated -> {
                    _toastMessage.value = "yt-dlp 업데이트를 적용했습니다."
                }
                is LibraryUpdateResult.UpToDate -> {
                    _toastMessage.value = "yt-dlp가 이미 최신 버전입니다."
                }
                is LibraryUpdateResult.Failed -> {
                    val errorMessage = result.error.message?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"
                    _toastMessage.value = "라이브러리 업데이트 실패: $errorMessage"
                }
            }
            _isUpdating.value = false
        }
    }
    
    fun resetState() {
        _uiState.value = UiState.Idle
    }

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    private val _isDownloading = MutableLiveData<Boolean>(false)
    val isDownloading: LiveData<Boolean> = _isDownloading

    fun updateDownloadStatus(message: String, isDownloading: Boolean) {
        _statusMessage.value = message
        _isDownloading.value = isDownloading

        if (!isDownloading && pendingLibraryUpdateCheck) {
            requestLibraryUpdateCheck()
        }
    }

    private val _updateInfo = MutableLiveData<AppUpdateInfo?>()
    val updateInfo: LiveData<AppUpdateInfo?> = _updateInfo

    private val _versionStatus = MutableLiveData<String>()
    val versionStatus: LiveData<String> = _versionStatus

    fun checkAppUpdate(currentVersion: String) {
        viewModelScope.launch {
            val updater = AppUpdater(getApplication())
            when (val result = updater.checkForUpdate(currentVersion)) {
                is AppUpdater.UpdateResult.Available -> {
                    _updateInfo.value = result.updateInfo
                    _versionStatus.value = "현재 버전: $currentVersion / 최신 버전: ${result.updateInfo.version} (업데이트 가능)"
                }
                is AppUpdater.UpdateResult.NoUpdate -> {
                    _versionStatus.value = "현재 버전: $currentVersion (최신 버전입니다)"
                }
                is AppUpdater.UpdateResult.Error -> {
                    _versionStatus.value = "현재 버전: $currentVersion (업데이트 확인 실패)"
                }
            }
        }
    }
}
