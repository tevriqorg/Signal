package com.wb.signalpro

import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.MediaType
import com.wb.signalpro.photos.CandidateBoundaryReason
import com.wb.signalpro.photos.CandidateGroup
import com.wb.signalpro.photos.CandidateGroupSegmenter
import com.wb.signalpro.photos.GalleryCloudHealth
import com.wb.signalpro.photos.GalleryDot
import com.wb.signalpro.photos.GalleryTransferBadge
import com.wb.signalpro.photos.MediaAccessLevel
import com.wb.signalpro.photos.MediaChangeObserver
import com.wb.signalpro.photos.MediaIndexCoordinator
import com.wb.signalpro.photos.MediaPermissionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #14 Android-first gallery. Only MediaStore is available in this increment.
 * Do not invent server assets, remote device names, or a green sync status until the
 * independently authenticated Signal Server flow is implemented and verified.
 */
class GalleryActivity : ComponentActivity() {
    private lateinit var coordinator: MediaIndexCoordinator
    private var observer: MediaChangeObserver? = null
    private val scope = MainScope()
    private var uiState by mutableStateOf(GalleryUiState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh(forceFull = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = MediaIndexCoordinator(this)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1C1C1E),
                    onPrimary = Color.White,
                    background = Color.White,
                    surface = Color.White,
                    onSurface = Color(0xFF1C1C1E),
                    error = Color(0xFFD94141),
                ),
            ) {
                GalleryScreen(
                    state = uiState,
                    onBack = { finish() },
                    onRequestAccess = { requestMediaAccess() },
                    onRefresh = { refresh(forceFull = true) },
                    onOpenVideo = { openVideo(it) },
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

    private fun openVideo(item: MediaItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.contentUri), item.mimeType ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    private fun refresh(forceFull: Boolean) {
        if (uiState.scanning) return
        scope.launch {
            uiState = uiState.copy(scanning = true, error = null)
            runCatching {
                val report = coordinator.reconcile(forceFull)
                val items = if (report.accessLevel == MediaAccessLevel.DENIED) emptyList()
                    else coordinator.indexedMedia()
                GalleryUiState(
                    accessLevel = report.accessLevel,
                    items = items,
                    scanning = false,
                    indexedThisPass = report.indexedRows,
                    error = null,
                )
            }.onSuccess {
                uiState = it
            }.onFailure {
                uiState = uiState.copy(scanning = false, error = it.message ?: it.javaClass.simpleName)
            }
        }
    }
}

private enum class GalleryFilter { PHOTOS, VIDEOS, SCREENSHOTS }

private data class GalleryUiState(
    val accessLevel: MediaAccessLevel = MediaAccessLevel.DENIED,
    val items: List<MediaItem> = emptyList(),
    val scanning: Boolean = false,
    val indexedThisPass: Int = 0,
    val error: String? = null,
)

private val SubtleGray = Color(0xFF8E8E93)
private val TileBg = Color(0xFFF2F2F7)
private val SyncAmber = Color(0xFFE5A32A)
private val SyncRed = Color(0xFFD94141)
private val SyncGreen = Color(0xFF2E995B)

@Composable
private fun GalleryScreen(
    state: GalleryUiState,
    onBack: () -> Unit,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
    onOpenVideo: (MediaItem) -> Unit,
) {
    var filter by remember { mutableStateOf(GalleryFilter.PHOTOS) }
    // G2 remains available in the overflow; default is the clean, chronological photo wall.
    var groupedView by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var openedItem by remember { mutableStateOf<MediaItem?>(null) }

    val visible = when (filter) {
        GalleryFilter.PHOTOS -> state.items.filter {
            it.mediaType == MediaType.PHOTO || it.mediaType == MediaType.SCREENSHOT
        }
        GalleryFilter.VIDEOS -> state.items.filter { it.mediaType == MediaType.VIDEO }
        GalleryFilter.SCREENSHOTS -> state.items.filter { it.mediaType == MediaType.SCREENSHOT }
    }
    val groups = remember(visible, groupedView) {
        if (groupedView) CandidateGroupSegmenter.segment(visible) else emptyList()
    }

    // No server endpoint/session exists in this increment: connection must not show green.
    val cloudHealth = GalleryCloudHealth.NEEDS_ATTENTION

    Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("‹ 返回", color = Color(0xFF3A78BB), fontSize = 15.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSync = true }) {
                    CloudGlyph(
                        color = if (cloudHealth == GalleryCloudHealth.HEALTHY) SyncGreen else SyncRed,
                        modifier = Modifier.size(25.dp),
                    )
                }
                Box {
                    TextButton(onClick = { showOverflow = true }) {
                        Text("···", color = Color(0xFF333338), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text(if (groupedView) "按时间排列" else "显示候选分组") },
                            onClick = { groupedView = !groupedView; showOverflow = false },
                        )
                        DropdownMenuItem(
                            text = { Text("重新扫描本机") },
                            onClick = { showOverflow = false; onRefresh() },
                        )
                        DropdownMenuItem(
                            text = { Text("同步与存储") },
                            onClick = { showOverflow = false; showSync = true },
                        )
                    }
                }
            }
            Text(
                "照片",
                modifier = Modifier.padding(start = 18.dp, bottom = 13.dp),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1C1C1E),
            )
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AlbumFilter("照片", filter == GalleryFilter.PHOTOS) { filter = GalleryFilter.PHOTOS }
                AlbumFilter("视频", filter == GalleryFilter.VIDEOS) { filter = GalleryFilter.VIDEOS }
                AlbumFilter("截图", filter == GalleryFilter.SCREENSHOTS) { filter = GalleryFilter.SCREENSHOTS }
            }

            if (state.accessLevel != MediaAccessLevel.FULL) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xFFF2F2F7))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.accessLevel == MediaAccessLevel.PARTIAL) "仅部分相册可访问"
                        else "请授权访问相册",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = Color(0xFF4C4C52),
                    )
                    TextButton(onClick = onRequestAccess) {
                        Text(if (state.accessLevel == MediaAccessLevel.PARTIAL) "调整" else "授权", fontSize = 12.sp)
                    }
                }
            }
            if (state.error != null) {
                Text(
                    "本机相册索引异常 · 可从右上角菜单重试",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    color = SyncRed,
                    fontSize = 12.sp,
                )
            }
            if (state.scanning && state.items.isNotEmpty()) {
                Text("更新照片中…", modifier = Modifier.padding(horizontal = 16.dp), color = SubtleGray, fontSize = 11.sp)
            }

            when {
                state.scanning && state.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SubtleGray)
                    }
                }
                state.accessLevel == MediaAccessLevel.DENIED -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("授权后即可浏览本机照片", color = SubtleGray)
                    }
                }
                visible.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("目前没有这一类照片或视频", color = SubtleGray)
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (groupedView) {
                            groups.forEach { group ->
                                item(key = "header:${group.id}", span = { GridItemSpan(3) }) {
                                    CandidateHeader(group)
                                }
                                items(group.items, key = { it.id.value }) { item ->
                                    MediaTile(item, GalleryTransferBadge.localPending(), onClick = { openedItem = item })
                                }
                            }
                        } else {
                            items(visible, key = { it.id.value }) { item ->
                                MediaTile(item, GalleryTransferBadge.localPending(), onClick = { openedItem = item })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSync) {
        AlertDialog(
            onDismissRequest = { showSync = false },
            title = { Text("同步与存储") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Signal Server 尚未接入", fontWeight = FontWeight.SemiBold, color = SyncRed)
                    Text("当前 Android 增量只读取本机授权相册，不上传任何照片。黄点表示原件尚未在服务器确认保存。", fontSize = 13.sp)
                    Text("已索引：${state.items.size} 项 · 本轮更新：${state.indexedThisPass} 项", fontSize = 13.sp)
                    state.error?.let { Text("索引错误：$it", color = SyncRed, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSync = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = { onRefresh(); showSync = false }) { Text("重新扫描") }
            },
        )
    }
    openedItem?.let { item ->
        GalleryViewer(
            item = item,
            onDismiss = { openedItem = null },
            onOpenVideo = { onOpenVideo(item) },
        )
    }
}

@Composable
private fun AlbumFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color(0xFF252528) else Color(0xFFF2F2F7))
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 8.dp),
        color = if (selected) Color.White else Color(0xFF44444A),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun CandidateHeader(group: CandidateGroup) {
    val reason = when (group.boundaryReason) {
        CandidateBoundaryReason.START -> "时间轴起点"
        CandidateBoundaryReason.TIME_GAP -> "较长时间间隔"
        CandidateBoundaryReason.SCREENSHOT_CAMERA_TRANSITION -> "截图与相机切换"
        CandidateBoundaryReason.BUCKET_TRANSITION -> "相册目录变化"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 16.dp, bottom = 7.dp)) {
        Text("候选分组 · ${group.items.size} 项", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(reason, fontSize = 11.sp, color = SubtleGray)
    }
}

@Composable
private fun MediaTile(item: MediaItem, badge: GalleryTransferBadge, onClick: () -> Unit) {
    val bitmap = rememberMediaBitmap(item.contentUri, 360)
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(TileBg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = item.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(if (item.mediaType == MediaType.VIDEO) "视频" else "照片", fontSize = 11.sp, color = SubtleGray)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (badge.source.showCloud) CloudGlyph(color = Color.White, modifier = Modifier.size(11.dp))
            Text(badge.source.label, fontSize = 9.sp, color = Color.White, maxLines = 1)
        }
        if (badge.dot != GalleryDot.NONE) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .size(8.dp)
                    .background(if (badge.dot == GalleryDot.RED) SyncRed else SyncAmber, CircleShape),
            )
        }
        if (item.mediaType == MediaType.VIDEO) {
            Text("▶", modifier = Modifier.align(Alignment.TopEnd).padding(7.dp), fontSize = 13.sp, color = Color.White)
        }
    }
}

@Composable
private fun rememberMediaBitmap(contentUri: String, maxSize: Int): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<ImageBitmap?>(initialValue = null, contentUri, maxSize) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                resolver.loadThumbnail(Uri.parse(contentUri), Size(maxSize, maxSize), null).asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
private fun GalleryViewer(
    item: MediaItem,
    onDismiss: () -> Unit,
    onOpenVideo: () -> Unit,
) {
    val bitmap = rememberMediaBitmap(item.contentUri, 1600)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f),
            shape = RoundedCornerShape(15.dp),
            color = Color(0xFF151517),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text("本机", color = Color.White, fontSize = 12.sp)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap = bitmap, contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text("无法读取预览", color = Color.White)
                }
                Text(item.displayName ?: "照片", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Text("原件尚未同步 · 未连接 Signal Server", color = Color(0xFFCBCBD0),
                    fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
                if (item.mediaType == MediaType.VIDEO) {
                    TextButton(onClick = onOpenVideo) { Text("用系统播放器打开", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun CloudGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cloud = Path().apply {
            moveTo(w * .24f, h * .79f)
            cubicTo(w * .07f, h * .79f, w * .02f, h * .68f, w * .07f, h * .55f)
            cubicTo(w * .10f, h * .41f, w * .21f, h * .36f, w * .34f, h * .39f)
            cubicTo(w * .39f, h * .18f, w * .55f, h * .13f, w * .68f, h * .21f)
            cubicTo(w * .80f, h * .27f, w * .83f, h * .37f, w * .84f, h * .45f)
            cubicTo(w * 1.00f, h * .47f, w * 1.00f, h * .66f, w * .93f, h * .73f)
            cubicTo(w * .87f, h * .79f, w * .84f, h * .79f, w * .77f, h * .79f)
            close()
        }
        drawPath(cloud, color)
    }
}
