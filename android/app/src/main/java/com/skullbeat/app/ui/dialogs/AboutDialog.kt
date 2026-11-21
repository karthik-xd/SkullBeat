package com.skullbeat.app.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.skullbeat.app.R
import com.skullbeat.app.ui.theme.ElectricBlue
import com.skullbeat.app.ui.theme.FredokaFontFamily
import com.skullbeat.app.ui.theme.RaisedDark
import com.skullbeat.app.ui.theme.SurfaceDark
import com.skullbeat.app.ui.theme.TextBone
import com.skullbeat.app.ui.theme.TextMuted

@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenThemeSelector: (() -> Unit)? = null
) {
    val updates = listOf(
        "Real-Time Song Mapping ETA & CSV Folder Import" to "Live estimated time to finish (ETA), mapping speed (songs/sec), and percentage progress when importing playlist links or CSVs. Choose individual CSV files or an entire folder of CSV playlists.",
        "Selectable App Theme Colors" to "Choose your style: Classic SkullBeat, Cyber Cyan, Sapphire Blue, Matrix Green, Neon Violet, Crimson Skull, or Sunset Gold. Shown on first startup and selectable anytime from Home, Library, or Settings.",
        "Downloads, Instant Offline Replay & Cancel" to "Dedicated Downloads library tab. Cancel downloads anytime from notification or app. 64KB high-speed download buffers. Instant offline playback anywhere in the app with zero internet and zero disk lag. Missing storage detection in Warning Yellow.",
        "Persistent Liked Songs & Auto-Recovery" to "Never lose liked songs when updating. Safe non-destructive database migrations, automatic persistent offline JSON backup, and one-tap restore in Library.",
        "New Songs Added to Top of Playlist" to "Songs added manually now appear at the top of the playlist for instant access. Bulk imports still append in album order at the bottom.",
        "Remove from Queue Feedback" to "Swipe left to remove a song from queue now shows 'Not in queue' if it was already not queued, so you always know what happened.",
        "Delete Playlist Confirmation" to "Deleted playlist now shows a confirmation dialog to prevent accidental deletes.",
        "Auto-Scroll on Song Add" to "Playlist screen auto-scrolls to the top when a new song is added so you can see it immediately.",
        "Bidirectional Swipe in Playlist" to "Slide right (Electric Blue to Cyan) to add to queue; slide left (Heart Red to Coral) to remove from queue with instant tactile feedback.",
        "Brand-Matched App Launcher Icon" to "Updated launcher icon with the official theme electric-blue gradient and bone-white skull symbol, plus adaptive and Material You themed icon support.",
        "Fixed Fast-Scrolling Playlist Crash" to "Replaced non-unique item keys with composite indexed keys, eliminated subcomposition drag delays, and isolated progress ticker recompositions.",
        "Fixed Storage Limits & LRU Asset Caching" to "Local disk caching for thumbnails (250MB) and audio playback assets (1GB) with item-by-item Least Recently Used (LRU) eviction for instant replays.",
        "Direct Playlist Photo Cover" to "Tap playlist cover to open quick options sheet, select any local photo from gallery, and automatically crop and persist in app storage.",
        "Fixed Swipe-to-Queue Overlap" to "Track rows have 100% solid opaque background with zero bleed-through or text overlap. Supports bidirectional swipe, spring back, and tactile haptic feedback.",
        "Custom Playlist Photo Covers" to "Select from gallery or snap a photo with camera for playlist covers with automatic 1:1 center-cropping and fast WebP compression.",
        "Smart Add-to-Playlist" to "Updated playlist picker with cover art previews and duplicate detection prevents duplicate song entries.",
        "Crash-Proof Stability" to "Eliminated background thread null-pointer and concurrent modification exceptions when adding tracks or browsing queues.",
        "Add Songs Direct to Playlist" to "Search and add songs directly into any playlist with one-tap instant feedback.",
        "Audio Flow Volume Sync" to "Automatic broadcast-grade loudness normalization syncs volume across all songs without sacrificing dynamic quality.",
        "Smart Multi-Touch Loop" to "Single touch loops song continuously. Double touch loops 1 time. Triple touch loops 2 times. Turn OFF anytime with a single touch.",
        "1-Touch Instant Play" to "Tap any song text to play immediately without double-pressing the play button.",
        "Spinning Skull Buffering" to "Play button spins a 360-degree skull animation while resolving YouTube streams.",
        "Solid Now Playing Sheet" to "Decreased transparency to 100% solid opaque background; eliminated list bleed-through."
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDark,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "SkullBeat",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "SkullBeat",
                                fontFamily = FredokaFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = TextBone
                            )
                            Text(
                                text = "Version 1.3.1 (Build 14)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(RaisedDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextBone,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RaisedDark)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "WHAT'S NEW IN THIS UPDATE",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = ElectricBlue
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                updates.forEach { (title, desc) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextBone
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (onOpenThemeSelector != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenThemeSelector()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Change Theme Color", color = TextBone, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (onOpenThemeSelector != null) RaisedDark else MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Got It", color = TextBone, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
