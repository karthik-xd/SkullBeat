package com.skullbeat.app.ui.screens.playlist

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skullbeat.app.domain.models.Track
import com.skullbeat.app.domain.util.TrackExporter
import com.skullbeat.app.ui.components.TrackListItem
import com.skullbeat.app.ui.theme.CyanAccent
import com.skullbeat.app.ui.theme.ElectricBlue
import com.skullbeat.app.ui.theme.HeartRed
import com.skullbeat.app.ui.theme.RaisedDark
import com.skullbeat.app.ui.theme.SurfaceDark
import com.skullbeat.app.ui.theme.TextBone
import com.skullbeat.app.ui.theme.TextMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onEditPlaylist: (com.skullbeat.app.domain.models.Playlist) -> Unit,
    onOpenImport: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playlist by viewModel.playlist.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val playlistId = viewModel.playlistId
    val currentTrackId by remember(viewModel.playbackState) {
        viewModel.playbackState
            .map { it.currentTrack?.videoId }
            .distinctUntilChanged()
    }.collectAsState(initial = viewModel.playbackState.value.currentTrack?.videoId)

    val favIds = remember(favorites) { favorites.map { it.videoId }.toSet() }
    var showAddSongsSheet by remember { mutableStateOf(false) }
    var showCoverOptionsSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Scroll to first song when tracks are added or updated
    LaunchedEffect(tracks.size) {
        if (tracks.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updateCoverPhoto(
                context = context,
                uri = uri,
                onSuccess = {
                    Toast.makeText(context, "Cover photo updated", Toast.LENGTH_SHORT).show()
                },
                onError = { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updateCoverPhoto(
                context = context,
                uri = uri,
                onSuccess = {
                    Toast.makeText(context, "Cover photo updated", Toast.LENGTH_SHORT).show()
                },
                onError = { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun launchPhotoPicker() {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (_: Exception) {
            try {
                getContentLauncher.launch("image/*")
            } catch (_: Exception) {
                Toast.makeText(context, "Cannot open photo picker", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RaisedDark)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = TextBone,
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Export Playlist CSV button (metadata only, zero audio download)
                IconButton(
                    onClick = {
                        if (tracks.isEmpty()) {
                            Toast.makeText(context, "No tracks in playlist to export", Toast.LENGTH_SHORT).show()
                        } else {
                            val name = playlist?.name?.ifBlank { "Playlist" } ?: "Playlist"
                            val content = TrackExporter.toCsv(tracks, name)
                            TrackExporter.shareExport(context, "csv", content, "text/csv", name)
                            Toast.makeText(context, "Exporting $name CSV (Metadata only — zero audio download)", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RaisedDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Export Playlist CSV",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Edit Playlist button
                IconButton(
                    onClick = {
                        playlist?.let { onEditPlaylist(it) }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RaisedDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Playlist",
                        tint = TextBone,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Delete Playlist button
                IconButton(
                    onClick = {
                        showDeleteConfirmDialog = true
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RaisedDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Playlist",
                        tint = HeartRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Playlist Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.skullbeat.app.ui.components.PlaylistCover(
                playlist = playlist,
                size = 96.dp,
                shape = RoundedCornerShape(14.dp),
                showEditBadge = true,
                modifier = Modifier.clickable {
                    showCoverOptionsSheet = true
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist?.name ?: "Playlist",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${tracks.size} tracks",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.playAll(shuffle = false) },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = TextBone,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Play", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { viewModel.playAll(shuffle = true) },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = TextBone,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Shuffle", color = TextBone, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { showAddSongsSheet = true },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add songs",
                            tint = CyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Add songs", color = CyanAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "This playlist is empty",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextBone
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Add songs from search or import from URL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { showAddSongsSheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "Add songs", tint = TextBone)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Add Songs", color = TextBone, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onOpenImport,
                            colors = ButtonDefaults.buttonColors(containerColor = RaisedDark)
                        ) {
                            Icon(imageVector = Icons.Default.FileDownload, contentDescription = "Import", tint = CyanAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Import URL", color = TextBone)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                itemsIndexed(
                    items = tracks,
                    key = { index, track -> "pl_${playlistId}_${track.videoId}_$index" },
                    contentType = { _, _ -> "track_item" }
                ) { index, track ->
                    TrackListItem(
                        track = track,
                        isCurrentTrack = currentTrackId == track.videoId,
                        isFavorite = favIds.contains(track.videoId),
                        onClick = { viewModel.playTrack(track, index) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onRemoveFromPlaylist = {
                            viewModel.removeTrack(track.videoId)
                            Toast.makeText(context, "Removed from playlist", Toast.LENGTH_SHORT).show()
                        },
                        onDownload = { onDownload(track) },
                        onArtistClick = onArtistClick,
                        onSwipeToQueue = {
                            viewModel.addToQueue(track)
                            Toast.makeText(context, "Added to queue: ${track.title}", Toast.LENGTH_SHORT).show()
                        },
                        onSwipeAction = { action ->
                            when (action) {
                                com.skullbeat.app.ui.components.SwipeAction.ADD_TO_QUEUE -> {
                                    viewModel.addToQueue(track)
                                    Toast.makeText(context, "Added to queue: ${track.title}", Toast.LENGTH_SHORT).show()
                                }
                                com.skullbeat.app.ui.components.SwipeAction.REMOVE_FROM_QUEUE -> {
                                    val removed = viewModel.removeFromQueue(track)
                                    val msg = if (removed) "Removed from queue: ${track.title}" else "Not in queue: ${track.title}"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                                com.skullbeat.app.ui.components.SwipeAction.PLAY_NEXT -> {
                                    viewModel.addToQueue(track)
                                    Toast.makeText(context, "Playing next: ${track.title}", Toast.LENGTH_SHORT).show()
                                }
                                com.skullbeat.app.ui.components.SwipeAction.ADD_TO_PLAYLIST -> {
                                    onAddToPlaylist(track)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Add Songs Bottom Sheet
    if (showAddSongsSheet) {
        val coroutineScope = rememberCoroutineScope()
        var searchQuery by remember { mutableStateOf("") }
        var isSearching by remember { mutableStateOf(false) }
        var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
        var debounceJob by remember { mutableStateOf<Job?>(null) }

        ModalBottomSheet(
            onDismissRequest = { showAddSongsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add Songs to \"${playlist?.name ?: "Playlist"}\"",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextBone
                        )
                    )
                    IconButton(onClick = { showAddSongsSheet = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(RaisedDark)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { newQ ->
                            searchQuery = newQ
                            debounceJob?.cancel()
                            if (newQ.trim().isNotBlank()) {
                                isSearching = true
                                debounceJob = coroutineScope.launch {
                                    delay(400)
                                    val res = viewModel.searchTracks(newQ)
                                    searchResults = res
                                    isSearching = false
                                }
                            } else {
                                searchResults = emptyList()
                                isSearching = false
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextBone),
                        cursorBrush = SolidColor(ElectricBlue),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search songs to add...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                searchQuery = ""
                                searchResults = emptyList()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = ElectricBlue, modifier = Modifier.size(32.dp))
                    }
                } else if (searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "Type song or artist name above" else "No songs found for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(searchResults, key = { "add_track_${it.videoId}" }) { candidateTrack ->
                            val copyCount = tracks.count { it.videoId == candidateTrack.videoId }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RaisedDark.copy(alpha = 0.6f))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = candidateTrack.thumbnailUrl,
                                    contentDescription = candidateTrack.title,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidateTrack.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = TextBone,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (copyCount > 0) "${candidateTrack.artist} • $copyCount in playlist" else candidateTrack.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (copyCount > 0) CyanAccent else TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (copyCount > 0) {
                                    IconButton(
                                        onClick = {
                                            viewModel.removeTrack(candidateTrack.videoId)
                                            Toast.makeText(
                                                context,
                                                "Removed 1 copy of \"${candidateTrack.title}\"",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove copy",
                                            tint = HeartRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        viewModel.addTrack(candidateTrack)
                                        val toastMsg = if (copyCount > 0) {
                                            "Added another copy (${copyCount + 1}) to ${playlist?.name ?: "Playlist"}"
                                        } else {
                                            "Added \"${candidateTrack.title}\" to ${playlist?.name ?: "Playlist"}"
                                        }
                                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = if (copyCount > 0) "Add another copy" else "Add",
                                        tint = ElectricBlue,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCoverOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCoverOptionsSheet = false },
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Playlist Cover",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextBone
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Option: Change Photo
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(RaisedDark)
                        .clickable {
                            showCoverOptionsSheet = false
                            launchPhotoPicker()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Change Photo",
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "Change Photo",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            color = TextBone
                        )
                    )
                }

                // Option: Remove Photo (if custom cover exists)
                if (!playlist?.coverPath.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(RaisedDark)
                            .clickable {
                                showCoverOptionsSheet = false
                                viewModel.removeCoverPhoto {
                                    Toast.makeText(context, "Cover photo removed", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove Photo",
                            tint = HeartRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Remove Photo",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                color = HeartRed
                            )
                        )
                    }
                }

                // Option: Edit Details
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(RaisedDark)
                        .clickable {
                            showCoverOptionsSheet = false
                            playlist?.let { onEditPlaylist(it) }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Details",
                        tint = TextBone,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "Edit Details",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            color = TextBone
                        )
                    )
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = SurfaceDark,
            title = {
                Text(
                    text = "Delete Playlist?",
                    color = TextBone,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${playlist?.name ?: "this playlist"}\"? This cannot be undone.",
                    color = TextMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deletePlaylist {
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HeartRed)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}
