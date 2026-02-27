package com.example.downloaderforkids

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.downloaderforkids.databinding.ActivityMainBinding
import com.yausername.youtubedl_android.mapper.VideoFormat
import androidx.lifecycle.lifecycleScope


data class FormatItem(
    val formatId: String,
    val container: String,
    val codec: String,
    val resolution: String,
    val fileSize: String
) {
    override fun toString(): String {
        return "[$formatId] $container ($codec) - $resolution ($fileSize)"
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    
    private var saveUri: Uri? = null
    private lateinit var dirPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    // Broadcast Receiver Definition
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                DownloadService.ACTION_DOWNLOAD_PROGRESS -> {
                    val message = intent.getStringExtra(DownloadService.EXTRA_STATUS_MESSAGE) ?: "다운로드 중..."
                    val isDownloading = intent.getBooleanExtra(DownloadService.EXTRA_IS_DOWNLOADING, true)
                    viewModel.updateDownloadStatus(message, isDownloading)
                }
                DownloadService.ACTION_DOWNLOAD_COMPLETE -> {
                    val message = intent.getStringExtra(DownloadService.EXTRA_STATUS_MESSAGE) ?: "완료"
                    val isDownloading = intent.getBooleanExtra(DownloadService.EXTRA_IS_DOWNLOADING, false)
                    viewModel.updateDownloadStatus(message, isDownloading)
                    binding.urlEditText.text.clear()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStateRestoration()
        setupLaunchers()
        setupListeners()
        setupObservers()
        
        handleSharedIntent(intent)
        checkNotificationPermission()
        
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        viewModel.checkAppUpdate(currentVersion)
    }

    private fun setupStateRestoration() {
        val sharedPref = getSharedPreferences("DownloaderPrefs", Context.MODE_PRIVATE)
        val savedUriString = sharedPref.getString("save_uri", null)
        if (savedUriString != null) {
            try {
                val uri = Uri.parse(savedUriString)
                saveUri = uri
                val docFile = DocumentFile.fromTreeUri(this, uri)
                binding.locationTextView.text = "저장위치: ${docFile?.name}"
            } catch (e: Exception) {
                // Ignore if URI is invalid
            }
        }
    }

    private fun setupLaunchers() {
        dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                saveUri = uri
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val docFile = DocumentFile.fromTreeUri(this, uri)
                binding.locationTextView.text = "저장위치: ${docFile?.name}"

                val sharedPref = getSharedPreferences("DownloaderPrefs", Context.MODE_PRIVATE)
                with(sharedPref.edit()) {
                    putString("save_uri", uri.toString())
                    apply()
                }
            }
        }

        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "알림 권한 허용됨", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "알림 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        binding.selectLocationButton.setOnClickListener { dirPickerLauncher.launch(null) }

        binding.downloadButton.setOnClickListener {
            if (saveUri == null) {
                Toast.makeText(this, "저장 위치를 먼저 선택하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = binding.urlEditText.text.toString()
            viewModel.fetchFormats(url)
        }

        // binding.updateButton.setOnClickListener { viewModel.updateLibrary() } // Removed
    }
    
    private fun setupObservers() {
        viewModel.isUpdating.observe(this) { isUpdating ->
            if (isUpdating) {
                binding.statusTextView.text = "라이브러리 업데이트 중..."
                binding.downloadButton.isEnabled = false
                binding.selectLocationButton.isEnabled = false
                binding.urlEditText.isEnabled = false
            } else {
                binding.statusTextView.text = "대기 중..."
                binding.downloadButton.isEnabled = true
                binding.selectLocationButton.isEnabled = true
                binding.urlEditText.isEnabled = true
            }
        }

        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Loading -> {
                    binding.statusTextView.text = "포맷 목록 분석 중..."
                    binding.downloadButton.isEnabled = false
                }
                is UiState.Success -> {
                    binding.statusTextView.text = "옵션을 선택하세요."
                    binding.downloadButton.isEnabled = true
                    showSelectionDialog(binding.urlEditText.text.toString(), state.videos, state.audios)
                    viewModel.resetState() // Reset to avoid showing dialog again on config change immediately
                }
                is UiState.Error -> {
                    binding.statusTextView.text = state.message
                    binding.downloadButton.isEnabled = true
                }
                is UiState.Idle -> {
                    // Do nothing or clear status
                }
            }
        }
        
        viewModel.toastMessage.observe(this) { message ->
            message?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.onToastShown()
            }
        }

        viewModel.statusMessage.observe(this) { message ->
            binding.statusTextView.text = message
        }

        viewModel.isDownloading.observe(this) { isDownloading ->
            binding.downloadButton.isEnabled = !isDownloading
            // binding.updateButton.isEnabled = !isDownloading // Removed
        }

        viewModel.updateInfo.observe(this) { updateInfo ->
            if (updateInfo != null) {
                showUpdateDialog(updateInfo)
            }
        }

        viewModel.versionStatus.observe(this) { status ->
            binding.versionTextView.text = status
        }
    }
    
    private fun showUpdateDialog(updateInfo: AppUpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle("새로운 버전 업데이트")
            .setMessage("새로운 버전(${updateInfo.version})이 있습니다.\n\n${updateInfo.releaseNotes}")
            .setPositiveButton("업데이트") { _, _ ->
                startUpdate(updateInfo.downloadUrl)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startUpdate(downloadUrl: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        val progressText = dialogView.findViewById<android.widget.TextView>(R.id.progressText)
        
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("다운로드 중...")
            .setView(dialogView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        val updater = AppUpdater(this)
        
        lifecycleScope.launchWhenStarted {
           val file = updater.downloadApk(downloadUrl) { progress ->
               runOnUiThread {
                   progressText.text = "다운로드 중... $progress%"
               }
           }
           progressDialog.dismiss()
           
           if (file != null) {
               updater.installApk(file)
           } else {
               Toast.makeText(this@MainActivity, "다운로드 실패", Toast.LENGTH_SHORT).show()
           }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_DOWNLOAD_PROGRESS)
            addAction(DownloadService.ACTION_DOWNLOAD_COMPLETE)
        }
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            // Ignored
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                val extractedUrl = extractUrl(sharedText)
                if (extractedUrl != null) {
                    binding.urlEditText.setText(extractedUrl)
                } else {
                    binding.urlEditText.setText(sharedText)
                }
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = "(https?://\\S+)".toRegex()
        val matchResult = regex.find(text)
        return matchResult?.value
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showSelectionDialog(url: String, videos: List<VideoFormat>, audios: List<VideoFormat>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_format, null)
        val spinnerVideo = dialogView.findViewById<Spinner>(R.id.spinnerVideo)
        val spinnerAudio = dialogView.findViewById<Spinner>(R.id.spinnerAudio)

        val videoItems = mutableListOf<FormatItem>()
        videoItems.add(FormatItem("none", "None", "None", "비디오 없음 (오디오만)", "N/A"))
        videoItems.addAll(videos.map {
            FormatItem(
                formatId = it.formatId ?: "",
                container = it.ext ?: "mp4",
                codec = it.vcodec ?: "unknown",
                resolution = "${it.width}x${it.height}",
                fileSize = formatFileSize(it.fileSize)
            )
        })

        val audioItems = audios.map {
            FormatItem(
                formatId = it.formatId ?: "",
                container = it.ext ?: "m4a",
                codec = it.acodec ?: "unknown",
                resolution = "Audio",
                fileSize = formatFileSize(it.fileSize)
            )
        }

        spinnerVideo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, videoItems)
        spinnerAudio.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, audioItems)

        val sharedPref = getSharedPreferences("DownloaderPrefs", Context.MODE_PRIVATE)
        val lastVideoId = sharedPref.getString("last_video_id", null)
        val lastAudioId = sharedPref.getString("last_audio_id", null)

        if (lastVideoId != null) {
            val videoIndex = videoItems.indexOfFirst { it.formatId == lastVideoId }
            if (videoIndex != -1) {
                spinnerVideo.setSelection(videoIndex)
            }
        }

        if (lastAudioId != null) {
            val audioIndex = audioItems.indexOfFirst { it.formatId == lastAudioId }
            if (audioIndex != -1) {
                spinnerAudio.setSelection(audioIndex)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("다운로드 옵션 선택")
            .setView(dialogView)
            .setPositiveButton("다운로드") { _, _ ->
                val selectedVideo = spinnerVideo.selectedItem as? FormatItem
                val selectedAudio = spinnerAudio.selectedItem as? FormatItem

                if (selectedVideo != null && selectedAudio != null) {
                    with(sharedPref.edit()) {
                        putString("last_video_id", selectedVideo.formatId)
                        putString("last_audio_id", selectedAudio.formatId)
                        apply()
                    }
                    startDownloadService(url, selectedVideo.formatId, selectedAudio.formatId)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startDownloadService(url: String, videoId: String, audioId: String) {
        val intent = Intent(this, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_VIDEO_ID, videoId)
            putExtra(DownloadService.EXTRA_AUDIO_ID, audioId)
            putExtra(DownloadService.EXTRA_SAVE_URI, saveUri.toString())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "백그라운드에서 다운로드가 시작됩니다.", Toast.LENGTH_SHORT).show()
        binding.urlEditText.text.clear()
        viewModel.updateDownloadStatus("다운로드 시작됨...", true) // Optimistic update
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "N/A"
        val mb = size / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }
}
