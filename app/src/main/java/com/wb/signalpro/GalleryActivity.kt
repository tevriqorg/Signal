package com.wb.signalpro

import android.net.Uri
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import com.wb.signalpro.photos.MediaAccessLevel
import com.wb.signalpro.photos.MediaChangeObserver
import com.wb.signalpro.photos.MediaIndexCoordinator
import com.wb.signalpro.photos.MediaPermissionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : ComponentActivity() {
    private lateinit var coordinator: MediaIndexCoordinator
    private var observer: MediaChangeObserver? = null
    private val scope = MainScope()
    private var uiState by mutableStateOf(GalleryUiState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh(forceFull = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = MediaIndexCoordinator(this)
        setContent {
            SignalTheme {
                GalleryScreen(
                    state = uiState,
                    onBack = { finish() },
                    onRequestAccess = { requestMediaAccess() },
                    onRefresh = { refresh(forceFull = true) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        observer = coordinator.newObserver { refresh(forceFull = false) }.also { it.register() }
    }

    override fun onResume() {
        super.onResume()
        refresh(forceFull = false)
    }

    override fun onStop() {
        observer?.unregister()
        observer = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        coordinator.close()
        super.onDestroy()
    }

    private fun requestMediaAccess() {
        val permissions = MediaPermissionPolicy.requestedPermissions()
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions)
    }

    private fun refresh(forceFull: Boolean) {
        if (uiState.syncing) return
        scope.launch {
            uiState = uiState.copy(syncing = true, error = null)
            runCatching {
                val report = coordinator.reconcile(forceFull)
                val items = if (report.accessLevel == MediaAccessLevel.DENIED) {
                    emptyList()
                } else {
                    coordinator.indexedMedia()
                }
                GalleryUiState(
                    accessLevel = report.accessLevel,
                    items = items,
                    syncing = false,
                    indexedThisPass = report.indexedRows,
                    error = null,
                )
            }.onSuccess {
                uiState = it
            }.onFailure {
                uiState = uiState.copy(syncing = false, error = it.message ?: it.javaClass.simpleName)
            }
        }
    }
}

private enum class GalleryFilter { PHOTOS, VIDEOS, SCREENSHOTS }

private data class GalleryUiState(
    val accessLevel: MediaAccessLevel = MediaAccessLevel.DENIED,
    val items: List<MediaItem> = emptyList(),
    val syncing: Boolean = false,
    val indexedThisPass: Int = 0,
    val error: String? = null,
)

@Composable
private fun GalleryScreen(
    state: GalleryUiState,
    onBack: () -> Unit,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
) {
    var filter by mutableStateOf(GalleryFilter.PHOTOS)
    val visible = when (filter) {
        GalleryFilter.PHOTOS -> state.items.filter { it.mediaType == MediaType.PHOTO || it.mediaType == MediaType.SCREENSHOT }
        GalleryFilter.VIDEOS -> state.items.filter { it.mediaType == MediaType.VIDEO }
        GalleryFilter.SCREENSHOTS -> state.items.filter { it.mediaType == MediaType.SCREENSHOT }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onBack) { Text("返回") }
                Text("Photos", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onRefresh, enabled = !state.syncing) { Text("刷新") }
            }

            AccessBanner(state.accessLevel, state.syncing, state.indexedThisPass, onRequestAccess)
            state.error?.let {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("索引失败：$it", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == GalleryFilter.PHOTOS, onClick = { filter = GalleryFilter.PHOTOS }, label = { Text("照片") })
                FilterChip(selected = filter == GalleryFilter.VIDEOS, onClick = { filter = GalleryFilter.VIDEOS }, label = { Text("视频") })
                FilterChip(selected = filter == GalleryFilter.SCREENSHOTS, onClick = { filter = GalleryFilter.SCREENSHOTS }, label = { Text("截图") })
            }

            if (state.syncing && state.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.accessLevel == MediaAccessLevel.DENIED) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("授权后才能建立本机 MediaStore 索引。")
                }
            } else if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前授权范围内没有这一类媒体。")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(visible, key = { it.id.value }) { item -> MediaTile(item) }
                }
            }
        }
    }
}

@Composable
private fun AccessBanner(
    access: MediaAccessLevel,
    syncing: Boolean,
    indexedThisPass: Int,
    onRequestAccess: () -> Unit,
) {
    val text = when (access) {
        MediaAccessLevel.FULL -> "完整相册访问 · 本轮索引 $indexedThisPass 项"
        MediaAccessLevel.PARTIAL -> "仅部分相册可访问 · Signal 不会把它显示成完整图库"
        MediaAccessLevel.DENIED -> "尚未授权相册"
    }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f))
            if (syncing) CircularProgressIndicator(modifier = Modifier.padding(start = 10.dp))
            if (access != MediaAccessLevel.FULL) {
                Button(onClick = onRequestAccess, modifier = Modifier.padding(start = 10.dp)) {
                    Text(if (access == MediaAccessLevel.DENIED) "授权" else "调整")
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaItem) {
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val bitmap by produceState<ImageBitmap?>(initialValue = null, item.contentUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                resolver.loadThumbnail(Uri.parse(item.contentUri), Size(360, 360), null).asImageBitmap()
            }.getOrNull()
        }
    }
    Card(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } ?: Text(if (item.mediaType == MediaType.VIDEO) "VIDEO" else "PHOTO")
            if (item.mediaType == MediaType.VIDEO) {
                Text("▶", modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
    }
}
