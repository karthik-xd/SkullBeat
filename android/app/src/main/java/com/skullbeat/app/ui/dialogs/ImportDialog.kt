package com.skullbeat.app.ui.dialogs

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.skullbeat.app.domain.repository.MusicRepository
import com.skullbeat.app.domain.util.CsvTrackParser
import com.skullbeat.app.domain.util.EtaEstimator
import com.skullbeat.app.ui.theme.CyanAccent
import com.skullbeat.app.ui.theme.ElectricBlue
import com.skullbeat.app.ui.theme.HeartRed
import com.skullbeat.app.ui.theme.RaisedDark
import com.skullbeat.app.ui.theme.SuccessGreen
import com.skullbeat.app.ui.theme.SurfaceDark
import com.skullbeat.app.ui.theme.TextBone
import com.skullbeat.app.ui.theme.TextMuted
import com.skullbeat.app.ui.theme.WarningYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ImportTab {
    LINK, CSV
}

enum class CsvDestination {
    PLAYLIST, LIKED_SONGS
}

@Composable
fun ImportDialog(
    initialUrl: String = "",
    initialTab: ImportTab = ImportTab.LINK,
    repository: MusicRepository,
    onImportSuccess: (playlistId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(initialTab) }
    var url by remember { mutableStateOf(initialUrl) }
    var isImporting by remember { mutableStateOf(false) }
    var progressStatus by remember { mutableStateOf("") }
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var etaText by remember { mutableStateOf("") }
    var currentProcessingSong by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // CSV Specific State
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var parsedCsvSongs by remember { mutableStateOf<List<CsvTrackParser.CsvRow>>(emptyList()) }
    var csvDestination by remember { mutableStateOf(CsvDestination.PLAYLIST) }
    var csvPlaylistName by remember {
        mutableStateOf("CSV Import ${SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date())}")
    }

    // Single or Multiple CSV files launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val allRows = mutableListOf<CsvTrackParser.CsvRow>()
                    var count = 0
                    var firstName = "songs.csv"
                    for (uri in uris) {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1 && cursor.moveToFirst()) {
                                if (count == 0) firstName = cursor.getString(nameIdx)
                            }
                        }
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val rows = CsvTrackParser.parse(stream)
                            allRows.addAll(rows)
                            count++
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (allRows.isEmpty()) {
                            errorMessage = "No song titles detected in selected CSV(s)."
                            parsedCsvSongs = emptyList()
                        } else {
                            parsedCsvSongs = allRows
                            selectedFileName = if (count == 1) firstName else "$count CSV files (${allRows.size} songs)"
                            val baseName = if (count == 1) firstName.substringBeforeLast(".") else "CSV Import (${allRows.size} songs)"
                            csvPlaylistName = baseName
                            if (baseName.lowercase().contains("liked") || baseName.lowercase().contains("favorite")) {
                                csvDestination = CsvDestination.LIKED_SONGS
                            }
                            errorMessage = null
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Failed to read CSV: ${e.localizedMessage}"
                    }
                }
            }
        }
    }

    // CSV Folder launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val docFolder = DocumentFile.fromTreeUri(context, uri)
                    if (docFolder != null && docFolder.isDirectory) {
                        val csvFiles = docFolder.listFiles().filter {
                            it.isFile && it.name?.endsWith(".csv", ignoreCase = true) == true
                        }
                        if (csvFiles.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "No .csv files found in folder '${docFolder.name}'."
                                parsedCsvSongs = emptyList()
                            }
                        } else {
                            val allRows = mutableListOf<CsvTrackParser.CsvRow>()
                            var count = 0
                            for (file in csvFiles) {
                                try {
                                    context.contentResolver.openInputStream(file.uri)?.use { stream ->
                                        val rows = CsvTrackParser.parse(stream)
                                        if (rows.isNotEmpty()) {
                                            allRows.addAll(rows)
                                            count++
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            withContext(Dispatchers.Main) {
                                if (allRows.isEmpty()) {
                                    errorMessage = "No valid songs found in ${csvFiles.size} CSV files."
                                    parsedCsvSongs = emptyList()
                                } else {
                                    parsedCsvSongs = allRows
                                    val folderName = docFolder.name ?: "CSV Folder"
                                    selectedFileName = "$folderName ($count CSV files)"
                                    csvPlaylistName = folderName
                                    if (folderName.lowercase().contains("liked") || folderName.lowercase().contains("favorite")) {
                                        csvDestination = CsvDestination.LIKED_SONGS
                                    }
                                    errorMessage = null
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Failed to read folder: ${e.localizedMessage}"
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        containerColor = SurfaceDark,
        title = {
            Column {
                Text(
                    text = "Import & Map Music",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Tab Switcher (Link vs CSV)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(RaisedDark)
                        .padding(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (activeTab == ImportTab.LINK) ElectricBlue else RaisedDark)
                            .clickable(enabled = !isImporting) { activeTab = ImportTab.LINK }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Link",
                                tint = if (activeTab == ImportTab.LINK) TextBone else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Playlist Link",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (activeTab == ImportTab.LINK) TextBone else TextMuted
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (activeTab == ImportTab.CSV) ElectricBlue else RaisedDark)
                            .clickable(enabled = !isImporting) { activeTab = ImportTab.CSV }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = "CSV",
                                tint = if (activeTab == ImportTab.CSV) TextBone else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Upload CSV / Folder",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (activeTab == ImportTab.CSV) TextBone else TextMuted
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (activeTab == ImportTab.LINK) {
                    Text(
                        text = "Paste a Spotify or YouTube playlist/album link to match audio streams with real-time ETA calculation.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(RaisedDark)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = url,
                            onValueChange = {
                                url = it
                                errorMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isImporting,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextBone),
                            cursorBrush = SolidColor(ElectricBlue),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (url.isEmpty()) {
                                    Text(
                                        text = "https://open.spotify.com/... or youtube.com/...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                } else {
                    // CSV Import UI
                    Text(
                        text = "Upload a CSV file or select an entire CSV folder. Each song is matched with real-time ETA and added to your library.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("text/*", "text/comma-separated-values", "application/csv", "*/*")) },
                            enabled = !isImporting,
                            colors = ButtonDefaults.buttonColors(containerColor = RaisedDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = "Choose CSV",
                                tint = ElectricBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CSV File(s)",
                                fontSize = 12.sp,
                                color = TextBone,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            enabled = !isImporting,
                            colors = ButtonDefaults.buttonColors(containerColor = RaisedDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = "Choose Folder",
                                tint = CyanAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CSV Folder",
                                fontSize = 12.sp,
                                color = TextBone,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (selectedFileName != null && parsedCsvSongs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(RaisedDark)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Valid",
                                tint = SuccessGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${parsedCsvSongs.size} songs ready from $selectedFileName",
                                fontSize = 12.sp,
                                color = TextBone,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Import Destination",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(RaisedDark)
                                .padding(3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (csvDestination == CsvDestination.PLAYLIST) ElectricBlue else RaisedDark)
                                    .clickable(enabled = !isImporting) { csvDestination = CsvDestination.PLAYLIST }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "📁 To Playlist",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (csvDestination == CsvDestination.PLAYLIST) TextBone else TextMuted
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (csvDestination == CsvDestination.LIKED_SONGS) HeartRed else RaisedDark)
                                    .clickable(enabled = !isImporting) { csvDestination = CsvDestination.LIKED_SONGS }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "❤️ To Liked Songs",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (csvDestination == CsvDestination.LIKED_SONGS) TextBone else TextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (csvDestination == CsvDestination.PLAYLIST) {
                            Text(
                                text = "Playlist Name",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RaisedDark)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                BasicTextField(
                                    value = csvPlaylistName,
                                    onValueChange = { csvPlaylistName = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isImporting,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextBone),
                                    cursorBrush = SolidColor(ElectricBlue),
                                    singleLine = true
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Maps audio streams without downloading full songs.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = TextMuted)
                            )
                        } else {
                            Text(
                                text = "All songs will be mapped and added to Liked Songs (Streaming ready — zero audio download).",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = TextMuted)
                            )
                        }
                    }
                }

                // Error Notice
                AnimatedVisibility(visible = errorMessage != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(RaisedDark)
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = if (errorMessage?.contains("Liked Songs") == true) WarningYellow else HeartRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, color = TextBone)
                        )
                    }
                }

                // Progress Bar & Real-Time ETA Card
                AnimatedVisibility(visible = isImporting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(RaisedDark)
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = progressStatus.ifBlank { "Mapping songs..." },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    color = TextBone,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (etaText.isNotBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = etaText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricBlue
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ElectricBlue,
                            trackColor = SurfaceDark
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${(progressFraction * 100).toInt()}% completed",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            )
                            if (currentProcessingSong.isNotBlank()) {
                                Text(
                                    text = currentProcessingSong,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (activeTab == ImportTab.LINK) {
                        val cleanUrl = url.trim()
                        if (cleanUrl.isBlank()) return@Button

                        if (cleanUrl.lowercase().contains("collection/tracks")) {
                            errorMessage = "Spotify Liked Songs is private. Make a public playlist from it and paste that link."
                            return@Button
                        }

                        isImporting = true
                        errorMessage = null
                        progressStatus = "Connecting to playlist..."
                        etaText = "Estimating time remaining..."
                        currentProcessingSong = ""
                        progressFraction = 0.05f

                        scope.launch {
                            try {
                                if (cleanUrl.contains("spotify.com") || cleanUrl.startsWith("spotify:")) {
                                    val etaEstimator = EtaEstimator()
                                    etaEstimator.start()
                                    val result = repository.importSpotify(cleanUrl) { processed, total ->
                                        scope.launch(Dispatchers.Main) {
                                            val info = etaEstimator.calculate(processed, total)
                                            progressStatus = "Mapping $processed / $total songs"
                                            etaText = info.fullSummary
                                            progressFraction = (processed.toFloat() / total.toFloat()).coerceIn(0.05f, 0.98f)
                                        }
                                    }

                                    val playlistId = repository.createPlaylist(
                                        name = result.playlistName,
                                        emoji = "🎧",
                                        colorHex = "#3D8BFF"
                                    )
                                    repository.addTracksToPlaylist(playlistId, result.tracks)

                                    withContext(Dispatchers.Main) {
                                        isImporting = false
                                        onImportSuccess(playlistId)
                                    }
                                } else {
                                    progressStatus = "Parsing YouTube playlist..."
                                    etaText = "Fetching stream metadata..."
                                    val (title, tracks) = repository.importYouTubePlaylist(cleanUrl)
                                    val playlistId = repository.createPlaylist(
                                        name = title,
                                        emoji = "▶️",
                                        colorHex = "#FF3B5C"
                                    )
                                    repository.addTracksToPlaylist(playlistId, tracks)

                                    withContext(Dispatchers.Main) {
                                        isImporting = false
                                        onImportSuccess(playlistId)
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isImporting = false
                                    errorMessage = e.localizedMessage ?: "Failed to import playlist"
                                }
                            }
                        }
                    } else {
                        // CSV Import Execution
                        if (parsedCsvSongs.isEmpty()) {
                            errorMessage = "Please choose a CSV file or folder first"
                            return@Button
                        }

                        isImporting = true
                        errorMessage = null
                        progressStatus = "Mapping songs from CSV..."
                        etaText = "Estimating time remaining..."
                        currentProcessingSong = ""
                        progressFraction = 0.05f

                        scope.launch {
                            try {
                                val etaEstimator = EtaEstimator()
                                etaEstimator.start()
                                val (title, matchedTracks) = repository.importCsvRows(parsedCsvSongs) { processed, total, currentSong ->
                                    scope.launch(Dispatchers.Main) {
                                        val info = etaEstimator.calculate(processed, total)
                                        progressStatus = "Mapping $processed / $total songs"
                                        currentProcessingSong = currentSong
                                        etaText = info.fullSummary
                                        progressFraction = (processed.toFloat() / total.toFloat()).coerceIn(0.05f, 0.98f)
                                    }
                                }

                                if (matchedTracks.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        isImporting = false
                                        errorMessage = "Could not find matches for songs in CSV."
                                    }
                                    return@launch
                                }

                                if (csvDestination == CsvDestination.LIKED_SONGS) {
                                    repository.addTracksToFavorites(matchedTracks)
                                    withContext(Dispatchers.Main) {
                                        isImporting = false
                                        android.widget.Toast.makeText(context, "Added ${matchedTracks.size} songs to Liked Songs (Streaming ready)!", android.widget.Toast.LENGTH_LONG).show()
                                        onImportSuccess(-1L)
                                    }
                                } else {
                                    val playlistName = csvPlaylistName.ifBlank { title }
                                    val playlistId = repository.createPlaylist(
                                        name = playlistName,
                                        emoji = "📁",
                                        colorHex = "#00E5FF"
                                    )
                                    repository.addTracksToPlaylist(playlistId, matchedTracks)

                                    withContext(Dispatchers.Main) {
                                        isImporting = false
                                        android.widget.Toast.makeText(context, "Created playlist '$playlistName' with ${matchedTracks.size} songs!", android.widget.Toast.LENGTH_SHORT).show()
                                        onImportSuccess(playlistId)
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isImporting = false
                                    errorMessage = e.localizedMessage ?: "Failed to process CSV"
                                }
                            }
                        }
                    }
                },
                enabled = !isImporting && if (activeTab == ImportTab.LINK) url.isNotBlank() else parsedCsvSongs.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(color = TextBone, modifier = Modifier.size(16.dp))
                } else {
                    Icon(
                        imageVector = if (activeTab == ImportTab.LINK) Icons.Default.FileDownload else Icons.Default.UploadFile,
                        contentDescription = "Import",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (activeTab == ImportTab.LINK) "Start Import" else "Map & Add to Library",
                        color = TextBone,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            if (!isImporting) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = TextMuted)
                }
            }
        }
    )
}
