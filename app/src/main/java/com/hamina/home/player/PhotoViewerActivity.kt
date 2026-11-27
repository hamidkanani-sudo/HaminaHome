package com.hamina.home.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.hamina.home.HaminaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/** Full-screen SMB photo viewer. The image is copied to the app cache before display. */
class PhotoViewerActivity : ComponentActivity() {

    private var imageFile by mutableStateOf<File?>(null)
    private var error by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        setContent {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loading -> CircularProgressIndicator(color = Color(0xFF42A5F5))
                    imageFile != null -> AsyncImage(
                        model = imageFile,
                        contentDescription = intent.getStringExtra(EXTRA_NAME) ?: "Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    else -> Text(
                        text = error ?: "Unable to open image",
                        color = Color.White,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        loadImage()
    }

    private fun loadImage() {
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (serverId.isNullOrBlank() || path.isNullOrBlank()) {
            loading = false
            error = "Image information is missing"
            return
        }

        lifecycleScope.launch {
            try {
                val server = HaminaApp.instance.serverRepository.getServersOnce()
                    .firstOrNull { it.id == serverId }
                    ?: throw IllegalStateException("SMB server was not found")

                val result = HaminaApp.instance.smbManager.openFileStream(server, path)
                val stream = result.getOrElse { throw it }
                val safeName = path.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(cacheDir, "photo_${System.currentTimeMillis()}_$safeName")
                withContext(Dispatchers.IO) {
                    // Remove copies left over from earlier photos so the cache does not grow forever.
                    cacheDir.listFiles { f -> f.name.startsWith("photo_") }?.forEach { it.delete() }
                    stream.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                }
                imageFile = target
            } catch (e: Exception) {
                error = e.message ?: "Could not load image from SMB server"
            } finally {
                loading = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) imageFile?.delete()
    }

    companion object {
        const val EXTRA_SERVER_ID = "photo_server_id"
        const val EXTRA_PATH = "photo_path"
        const val EXTRA_NAME = "photo_name"
    }
}
