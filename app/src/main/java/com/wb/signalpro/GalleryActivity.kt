package com.wb.signalpro

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import com.wb.signalpro.photos.CandidateGroupSegmenter
import com.wb.signalpro.photos.GalleryDot
import com.wb.signalpro.photos.GalleryOrigin
import com.wb.signalpro.photos.GallerySource
import com.wb.signalpro.photos.GallerySyncEngine
import com.wb.signalpro.photos.GalleryBackgroundScheduler
import com.wb.signalpro.photos.GallerySyncSnapshot
import com.wb.signalpro.photos.GalleryTransferBadge
import com.wb.signalpro.photos.GalleryTransferState
import com.wb.signalpro.photos.LocalTransfer
import com.wb.signalpro.photos.MediaAccessLevel
import com.wb.signalpro.photos.MediaChangeObserver
import com.wb.signalpro.photos.MediaIndexCoordinator
import com.wb.signalpro.photos.MediaPermissionPolicy
import com.wb.signalpro.photos.verifiedGalleryOriginal
import com.wb.signalpro.photos.RemotePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Subtle = Color(0xFF8E8E93)
private val SurfaceTint = Color(0xFFF2F2F7)
private val Amber = Color(0xFFE5A32A)
private val Red = Color(0xFFD94141)
private val Green = Color(0xFF26975E)
private val Blue = Color(0xFF3179C8)

class GalleryActivity : ComponentActivity() {
    private lateinit var coordinator: MediaIndexCoordinator
    private lateinit var sync: GallerySyncEngine
    private var observer: MediaChangeObserver? = null
    private val scope = MainScope()
    private var ui by mutableStateOf(GalleryUiState())

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh(forceFull = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = MediaIndexCoordinator(this)
        sync = GallerySyncEngine(this)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        ui = ui.copy(sync = sync.snapshot(false, "尚未连接"))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF212126), onPrimary = Color.White,
                background = Color.White, surface = Color.White, onSurface = Color(0xFF212126),
            )) {
                GalleryScreen(ui,
                    onBack = { finish() },
                    onRequestAccess = { requestMediaAccess() },
                    onRefresh = { refresh(forceFull = true) },
                    onSync = { startSync(ui.items) },
                    onPair = { url, code, device -> pair(url, code, device) },
                    onSearch = { search(it) },
                    onLoadRemote = { loadOriginal(it) },
                    onOpenVideo = { openVideo(it) },
                    onRemotePreview = { remote -> sync.loadPreview(remote) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        sync.foreground = true
        if (sync.client.paired) GalleryBackgroundScheduler.schedule(this)
        observer = coordinator.newObserver { refresh(false) }.also { it.register() }
    }

    override fun onResume() { super.onResume(); refresh(false) }
    override fun onStop() {
        sync.foreground = false
        observer?.unregister()
        observer = null
        super.onStop()
    }
    override fun onDestroy() {
        sync.close()
        coordinator.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun requestMediaAccess() {
        val permissions = MediaPermissionPolicy.requestedPermissions()
        if (permissions.isNotEmpty()) requestPermissions.launch(permissions)
    }

    private fun refresh(forceFull: Boolean) {
        if (ui.scanning) return
        scope.launch {
            ui = ui.copy(scanning = true, indexError = null)
            runCatching {
                val report = coordinator.reconcile(forceFull)
                val items = if (report.accessLevel == MediaAccessLevel.DENIED) emptyList()
                    else coordinator.indexedMedia()
                Triple(report.accessLevel, items, report.indexedRows)
            }.onSuccess { (access, items, count) ->
                ui = ui.copy(access = access, items = items, indexed = count, scanning = false)
                startSync(items)
            }.onFailure {
                ui = ui.copy(scanning = false, indexError = it.message)
            }
        }
    }

    private fun startSync(items: List<MediaItem>) {
        if (ui.syncRunning) return
        scope.launch {
            ui = ui.copy(syncRunning = true)
            try {
                sync.synchronize(items) { snapshot ->
                    scope.launch { ui = ui.copy(sync = snapshot) }
                }
            } finally { ui = ui.copy(syncRunning = false) }
        }
    }

    private fun pair(url: String, key: String, name: String) {
        scope.launch {
            ui = ui.copy(sync = ui.sync.copy(description = "正在连接服务器…"))
            runCatching { sync.pair(url, key, name) }
                .onSuccess {
                    ui = ui.copy(sync = it)
                    GalleryBackgroundScheduler.schedule(this@GalleryActivity)
                    if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                    }
                    startSync(ui.items)
                }.onFailure {
                    ui = ui.copy(sync = ui.sync.copy(healthy = false,
                        description = "配对失败：" + (it.message ?: "未知错误")))
                }
        }
    }

    private fun search(query: String) {
        scope.launch {
            if (query.isBlank()) {
                ui = ui.copy(searchResults = null)
            } else {
                runCatching { sync.search(query) }
                    .onSuccess { ui = ui.copy(searchResults = it, searchError = null) }
                    .onFailure { ui = ui.copy(searchResults = emptyList(), searchError = it.message) }
            }
        }
    }

    private fun loadOriginal(media: RemotePhoto) {
        ui = ui.copy(selected = Selected.Remote(media), loadingRemote = true, remoteError = null)
        scope.launch {
            runCatching {
                sync.loadOriginal(media) { done, total ->
                    scope.launch {
                        ui = ui.copy(sync = ui.sync.copy(transferredBytes = done,
                            totalBytes = total, activeTransfer = media.fileName))
                    }
                }
            }.onSuccess {
                ui = ui.copy(loadingRemote = false, selected = Selected.Remote(media),
                    remoteError = null, sync = sync.snapshot(true, "完整原件已缓存"))
            }.onFailure {
                ui = ui.copy(loadingRemote = false, remoteError = it.message ?: "下载失败", sync = sync.snapshot(false, "下载失败，请重试"))
            }
        }
    }

    private fun openVideo(item: MediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.contentUri), item.mimeType ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }
}

private data class GalleryUiState(
    val access: MediaAccessLevel = MediaAccessLevel.DENIED,
    val items: List<MediaItem> = emptyList(),
    val indexed: Int = 0,
    val scanning: Boolean = false,
    val indexError: String? = null,
    val sync: GallerySyncSnapshot = GallerySyncSnapshot(),
    val syncRunning: Boolean = false,
    val selected: Selected? = null,
    val loadingRemote: Boolean = false,
    val remoteError: String? = null,
    val searchResults: List<RemotePhoto>? = null,
    val searchError: String? = null,
)

private sealed class Selected {
    data class Local(val item: MediaItem): Selected()
    data class Remote(val item: RemotePhoto): Selected()
}
private sealed class Tile {
    data class Local(val item: MediaItem): Tile()
    data class Remote(val item: RemotePhoto): Tile()
    val key: String get() = when (this) {
        is Local -> "local:" + item.id.value
        is Remote -> "remote:" + item.id
    }
    val time: Long get() = when (this) {
        is Local -> item.capturedAtEpochMs ?: item.addedAtEpochMs
        is Remote -> item.capturedAt
    }
}

@Composable
private fun GalleryScreen(
    state: GalleryUiState,
    onBack: () -> Unit,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onPair: (String, String, String) -> Unit,
    onSearch: (String) -> Unit,
    onLoadRemote: (RemotePhoto) -> Unit,
    onOpenVideo: (MediaItem) -> Unit,
    onRemotePreview: suspend (RemotePhoto) -> java.io.File,
) {
    var filter by remember { mutableStateOf("照片") }
    var grouped by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var localSelected by remember { mutableStateOf<MediaItem?>(null) }
    var remoteSelected by remember { mutableStateOf<RemotePhoto?>(null) }
    var remoteFailed by remember { mutableStateOf(setOf<String>()) }

    val remote = state.searchResults ?: state.sync.remote
    val tiles = remember(state.items, remote, filter) {
        (state.items.map { Tile.Local(it) } + remote.map { Tile.Remote(it) })
            .filter {
                val kind = when (it) {
                    is Tile.Local -> it.item.mediaType.name.lowercase()
                    is Tile.Remote -> it.item.kind.lowercase()
                }
                when (filter) {
                    "视频" -> kind == "video"
                    "截图" -> kind == "screenshot"
                    else -> kind != "video"
                }
            }.sortedByDescending { it.time }
    }

    Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("‹ 返回", color = Blue, fontSize = 15.sp) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSync = true }) {
                    Text("☁", color = if (state.sync.healthy) Green else Red, fontSize = 24.sp)
                }
                Box {
                    TextButton(onClick = { overflow = true }) {
                        Text("···", fontSize = 23.sp, color = Color(0xFF242428))
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(text = { Text("搜索相册") }, onClick = { overflow = false; showSearch = true })
                        DropdownMenuItem(text = { Text(if (grouped) "显示原始时间轴" else "显示候选分组") },
                            onClick = { grouped = !grouped; overflow = false })
                        DropdownMenuItem(text = { Text("重新扫描与同步") },
                            onClick = { overflow = false; onRefresh() })
                    }
                }
            }
            Text("照片", Modifier.padding(start = 17.dp, bottom = 12.dp),
                fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color(0xFF202024))
            Row(Modifier.padding(start = 13.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("照片", "视频", "截图").forEach { name ->
                    Text(name,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (filter == name) Color(0xFF252529) else SurfaceTint)
                            .clickable { filter = name }.padding(horizontal = 17.dp, vertical = 8.dp),
                        color = if (filter == name) Color.White else Color(0xFF48484F),
                        fontSize = 13.sp)
                }
            }
            if (state.access != MediaAccessLevel.FULL) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp)).background(SurfaceTint)
                    .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.access == MediaAccessLevel.PARTIAL) "仅部分相册可访问" else "授权后才能浏览本机相册",
                        fontSize = 12.sp, color = Subtle, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRequestAccess) { Text("调整", fontSize = 12.sp, color = Blue) }
                }
            }
            if (state.indexError != null) {
                Text("索引异常：" + state.indexError, color = Red, fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp))
            }
            if (state.scanning && tiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Subtle)
                }
            } else if (tiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前还没有这一类照片", color = Subtle)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (grouped) {
                        // Preserve G2's deterministic grouping as an optional diagnostic layer.
                        val local = tiles.filterIsInstance<Tile.Local>().map { it.item }
                        CandidateGroupSegmenter.segment(local).forEach { group ->
                            item(key = "group:" + group.id, span = { GridItemSpan(3) }) {
                                Text("候选事件 · " + group.items.size + " 项",
                                    Modifier.padding(start = 13.dp, top = 15.dp, bottom = 7.dp),
                                    fontSize = 14.sp, color = Subtle)
                            }
                            items(group.items, key = { "local:" + it.id.value }) { item ->
                                GalleryCell(item, localBadge(item, state.sync.local[item.id.value])) {
                                    localSelected = item
                                }
                            }
                        }
                        items(tiles.filterIsInstance<Tile.Remote>(), key = { it.key }) { tile ->
                            val media = tile.item
                            RemoteCell(media, remoteBadge(media, state.sync, remoteFailed),
                                onRemotePreview, onClick = { remoteSelected = media; onLoadRemote(media) },
                                onFailed = { remoteFailed = remoteFailed + media.id })
                        }
                    } else {
                        // Plain native-style calendar timeline (no semantic header cards).
                        tiles.groupBy { tile ->
                            if (tile.time <= 0L) "日期未知"
                            else android.text.format.DateFormat.format("yyyy年M月d日", java.util.Date(tile.time)).toString()
                        }.forEach { (day, section) ->
                            item(key = "day:" + day, span = { GridItemSpan(3) }) {
                                Text(day, Modifier.padding(start = 14.dp, top = 15.dp, bottom = 7.dp),
                                    color = Color(0xFF4C4C52), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            items(section, key = { it.key }) { tile ->
                                when (tile) {
                                    is Tile.Local -> GalleryCell(tile.item,
                                        localBadge(tile.item, state.sync.local[tile.item.id.value])) {
                                            localSelected = tile.item
                                        }
                                    is Tile.Remote -> {
                                        val media = tile.item
                                        RemoteCell(media, remoteBadge(media, state.sync, remoteFailed),
                                            onRemotePreview,
                                            onClick = { remoteSelected = media; onLoadRemote(media) },
                                            onFailed = { remoteFailed = remoteFailed + media.id })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSync) {
        AlertDialog(onDismissRequest = { showSync = false },
            title = { Text("同步与存储") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(state.sync.description,
                        color = if (state.sync.healthy) Green else Red, fontWeight = FontWeight.SemiBold)
                    Text("本机索引：" + state.items.size + " 项 · 其他设备：" + state.sync.remote.size + " 项",
                        fontSize = 13.sp)
                    Text("待上传：" + state.sync.pendingUploads + " · 异常：" + state.sync.failures,
                        fontSize = 13.sp)
                    if (state.sync.activeTransfer.isNotBlank() && state.sync.totalBytes > 0)
                        Text("传输：" + state.sync.activeTransfer + " · " +
                            (state.sync.transferredBytes * 100 / state.sync.totalBytes) + "%",
                            fontSize = 13.sp)
                    Text(if (state.sync.paired) "服务器已配对" else "尚未配对服务器",
                        fontSize = 12.sp, color = Subtle)
                    if (!state.sync.paired)
                        TextButton(onClick = { showSync = false; showPair = true }) { Text("配对 Signal Server") }
                    Text("视频原件优先在高速 Wi-Fi 且 App 保持前台时传输。",
                        fontSize = 12.sp, color = Subtle)
                }
            },
            confirmButton = {
                TextButton(onClick = { onSync(); showSync = false }) { Text("重试同步") }
            },
            dismissButton = {
                TextButton(onClick = { showSync = false }) { Text("关闭") }
            })
    }

    if (showPair) {
        var url by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        var device by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
        AlertDialog(
            onDismissRequest = { showPair = false },
            title = { Text("连接私人相册服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("HTTPS 服务器地址") },
                        singleLine = true)
                    OutlinedTextField(value = device, onValueChange = { device = it }, label = { Text("设备名") },
                        singleLine = true)
                    OutlinedTextField(value = secret, onValueChange = { secret = it },
                        label = { Text("配对密钥") }, visualTransformation = PasswordVisualTransformation(),
                        singleLine = true)
                    Text("配对完成后使用 Android KeyStore 保存设备凭据；配对密钥不存储。",
                        fontSize = 11.sp, color = Subtle)
                }
            },
            confirmButton = {
                TextButton(onClick = { showPair = false; onPair(url, secret, device) },
                    enabled = url.isNotBlank() && secret.length >= 16 && device.isNotBlank()) { Text("连接") }
            },
            dismissButton = { TextButton(onClick = { showPair = false }) { Text("取消") } }
        )
    }

    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("搜索照片") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text("关键词") }, singleLine = true)
                    Text("当前支持文件名、设备名和元数据检索；AI 视觉理解尚未接入。",
                        color = Subtle, fontSize = 11.sp)
                    state.searchError?.let { Text(it, color = Red, fontSize = 11.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { onSearch(query); showSearch = false }) { Text("搜索") } },
            dismissButton = { TextButton(onClick = { onSearch(""); showSearch = false }) { Text("清除筛选") } }
        )
    }

    localSelected?.let { item ->
        MediaViewer(item.contentUri, item.displayName ?: "本机照片", "本机原件",
            onClose = { localSelected = null },
            isVideo = item.mediaType == MediaType.VIDEO,
            onOpenVideo = { onOpenVideo(item) })
    }
    remoteSelected?.let { media ->
        val context = LocalContext.current
        val file = remember(state.loadingRemote, media.id) {
            verifiedGalleryOriginal(context, media)
        }
        if (state.loadingRemote) {
            AlertDialog(onDismissRequest = { remoteSelected = null },
                title = { Text("下载原件中…") },
                text = { CircularProgressIndicator() },
                confirmButton = { TextButton(onClick = { remoteSelected = null }) { Text("隐藏") } })
        } else if (file == null) {
            AlertDialog(onDismissRequest = { remoteSelected = null },
                title = { Text("暂时无法打开原件") },
                text = { Text(state.remoteError ?: "云端原件仍在等待上传；此时不会自动误报完成。") },
                confirmButton = { TextButton(onClick = { remoteSelected = null }) { Text("关闭") } })
        } else {
            MediaViewer("file://" + file.absolutePath,
                media.deviceName + " · " + media.fileName, "完整原件已缓存",
                onClose = { remoteSelected = null }, isVideo = media.isVideo,
                onOpenVideo = {
                    val video = android.widget.VideoView(context)
                    val controls = android.widget.MediaController(context)
                    video.setMediaController(controls)
                    video.setVideoURI(Uri.fromFile(file))
                    val dialog = android.app.AlertDialog.Builder(context).setView(video)
                        .setPositiveButton("关闭") { _, _ -> video.stopPlayback() }.create()
                    dialog.setOnDismissListener { video.stopPlayback() }
                    video.setOnPreparedListener { video.start() }
                    dialog.show()
                })
        }
    }
}

private fun localBadge(item: MediaItem, transfer: LocalTransfer?): GalleryTransferBadge {
    val state = when {
        transfer?.verified == true -> GalleryTransferState.ORIGINAL_VERIFIED
        transfer?.failed == true -> GalleryTransferState.UPLOAD_FAILED
        transfer == null -> GalleryTransferState.NOT_REGISTERED
        else -> GalleryTransferState.METADATA_ONLY
    }
    return GalleryTransferBadge(GallerySource(GalleryOrigin.THIS_DEVICE, "本机"), state)
}

@Composable
private fun remoteBadge(media: RemotePhoto, sync: GallerySyncSnapshot, failed: Set<String>): GalleryTransferBadge {
    val context = LocalContext.current
    val original = remember(sync.remote, media.id) {
        verifiedGalleryOriginal(context, media)
    }
    val state = when {
        media.id in failed || media.id in sync.remoteFailures -> GalleryTransferState.DOWNLOAD_FAILED
        original != null -> GalleryTransferState.ORIGINAL_CACHED
        else -> GalleryTransferState.PREVIEW_ONLY
    }
    return GalleryTransferBadge(
        GallerySource(GalleryOrigin.OTHER_DEVICE, media.deviceName), state)
}

@Composable
private fun GalleryCell(item: MediaItem, badge: GalleryTransferBadge, onClick: () -> Unit) {
    val preview = mediaBitmap(item.contentUri, 360)
    GalleryTile(preview, item.displayName ?: "照片", badge,
        item.mediaType == MediaType.VIDEO, onClick)
}

@Composable
private fun RemoteCell(media: RemotePhoto, badge: GalleryTransferBadge,
    loadPreview: suspend (RemotePhoto) -> java.io.File, onClick: () -> Unit, onFailed: () -> Unit) {
    val path by produceState<String?>(initialValue = null, media.id, media.thumbReady) {
        value = runCatching { loadPreview(media).absolutePath }
            .onFailure { if (media.thumbReady) onFailed() }.getOrNull()
    }
    val preview = mediaBitmap(path?.let { "file://" + it }, 360)
    GalleryTile(preview, media.fileName, badge, media.isVideo, onClick)
}

@Composable
private fun GalleryTile(image: ImageBitmap?, description: String, badge: GalleryTransferBadge,
    video: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(SurfaceTint).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (image == null) Text(if (video) "视频" else "照片", color = Subtle, fontSize = 10.sp)
        else Image(image, contentDescription = description, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                .clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.48f))
                .padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(if (badge.source.showCloud) "☁ " + badge.source.label else "▯ 本机",
                maxLines = 1, fontSize = 9.sp, color = Color.White)
        }
        if (badge.dot != GalleryDot.NONE) {
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .size(8.dp).background(if (badge.dot == GalleryDot.RED) Red else Amber, CircleShape))
        }
        if (video) {
            Text("▶", modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun mediaBitmap(uri: String?, size: Int): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    val result by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            if (uri == null) null
            else runCatching {
                if (uri.startsWith("file://"))
                    BitmapFactory.decodeFile(uri.removePrefix("file://"))?.asImageBitmap()
                else resolver.loadThumbnail(Uri.parse(uri), Size(size, size), null).asImageBitmap()
            }.getOrNull()
        }
    }
    return result
}

@Composable
private fun MediaViewer(uri: String, name: String, subhead: String,
    onClose: () -> Unit, isVideo: Boolean, onOpenVideo: () -> Unit) {
    val image = mediaBitmap(uri, 1600)
    Dialog(onDismissRequest = onClose) {
        Surface(color = Color(0xFF151517), shape = RoundedCornerShape(17.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("关闭", color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text(subhead, color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.padding(end = 16.dp))
                }
                Box(Modifier.fillMaxWidth().aspectRatio(0.82f),
                    contentAlignment = Alignment.Center) {
                    if (image != null) Image(image, contentDescription = name,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text("无法预览当前原件", color = Color.White)
                }
                Text(name, Modifier.padding(12.dp), color = Color.White, fontSize = 12.sp)
                if (isVideo) TextButton(onClick = onOpenVideo) { Text("使用系统播放器打开") }
            }
        }
    }
}
