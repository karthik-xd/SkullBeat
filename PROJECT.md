# Project: Playlist Fast-Scrolling Crash Fix & Custom Local Cover Photo

## Architecture
- Android application using Kotlin, Jetpack Compose, Hilt, Room Database, and Coil 2.x for image loading.
- **UI Layer**:
  - `PlaylistDetailScreen.kt`: Renders playlist header (cover, title, stats, action buttons) and track list via `LazyColumn`.
  - `TrackListItem.kt`: Individual track row with album thumbnail (`AsyncImage`), song metadata, and swipe-to-queue gesture.
  - `SwipeToQueueBox.kt`: Gesture wrapper for queueing songs.
  - `PlaylistCover.kt`: Renders playlist cover using `SubcomposeAsyncImage` from local file (`coverPath`) or fallback emoji background. Includes responsive camera badge edit affordance.
  - `CreateEditPlaylistDialog.kt`: Modal dialog for editing playlist details and selecting custom cover photo via Photo Picker.
- **Data & Domain Layer**:
  - `Playlist.kt`: Entity & domain model containing `id`, `name`, `emoji`, `colorHex`, `coverPath`, `createdAt`.
  - `PlaylistTrack.kt`: Junction entity `(playlistId, videoId, position)`.
  - `PlaylistDao.kt`: Room DAO queries (`updatePlaylistCover`, `updatePlaylistFull`, `getPlaylistFlow`, `getPlaylistTracksFlow`).
  - `AppDatabase.kt`: Room database (v2) with `MIGRATION_1_2`.
  - `ImageUtils.kt`: Processes user-selected images (EXIF rotation, 1:1 crop, 512x512 scale, WEBP compression to `context.filesDir/playlist_covers/`). Safe old cover cleanup.
  - `SkullBeatApp.kt`: Application class configuring global services and Coil `ImageLoaderFactory`.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Unique LazyColumn Keying | Use unique composite key `pl_${playlistId}_${track.videoId}_$index` and `contentType` to prevent duplicate key crashes during fast scroll | M1 | Survey / R1 (DONE) |
| 2 | Viewport Constraint Fix | Change `LazyColumn` modifier from `fillMaxSize()` to `fillMaxWidth().weight(1f)` inside parent Column | M1 | Survey / R1 (DONE) |
| 3 | Remove Subcomposition in Track Items | Replace `BoxWithConstraints` in `SwipeToQueueBox` with standard `Box` and `Modifier.onSizeChanged` to avoid SubcomposeLayout disposal crashes under fast fling | M1 | Survey / R1 (DONE) |
| 4 | Coil ImageLoader Configuration & Downsampling | Implement `ImageLoaderFactory` in `SkullBeatApp` with memory/disk caching, downsample thumbnail requests in `TrackListItem`, and sanitize blank URLs | M1 | Survey / R1 (DONE) |
| 5 | Recomposition Storm Mitigation | Isolate current playing track ID in `PlaylistDetailScreen` to prevent 250ms ticker from recomposing the entire track list during flings | M1 | Survey / R1 (DONE) |
| 6 | Custom Cover Photo Selection | Allow user to pick local photo via modern Photo Picker (`PickVisualMedia`) with fallback (`GetContent`) from PlaylistDetailScreen | M2 | Survey / R2 (DONE) |
| 7 | Cover Photo Persistence & Display | Process and persist selected photo into private app storage (`context.filesDir/playlist_covers/`), update Room DB `coverPath`, and render reactively | M2 | Survey / R2 (DONE) |
| 8 | Cover Photo Visual Affordance & Removal | Add edit/camera badge overlay on cover in `PlaylistDetailScreen` and allow updating/removing cover | M2 | Survey / R2 (DONE) |
| 9 | Automated Verification Suite | Unit tests and programmatic checks for duplicate track scroll stability, Room cover persistence, and ImageUtils processing | M3 | Survey / AC (DONE) |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: Fix Playlist Fast-Scrolling Crash | Unique keying in `PlaylistDetailScreen`, viewport weight, `SwipeToQueueBox` subcomposition fix, `SkullBeatApp` Coil ImageLoader, and `TrackListItem` downsampling | none | DONE |
| 2 | M2: Custom Local Playlist Cover Photo | `PlaylistDetailScreen` cover click trigger/affordance, Photo Picker flow, `ImageUtils` persistence, and Room DB cover update | M1 | DONE |
| 3 | M3: Verification & Test Hardening | Automated unit tests for playlist scrolling stability with duplicate tracks, cover photo persistence, Room migration, and full build verification | M1, M2 | DONE |

## Interface Contracts
### `PlaylistDetailScreen.kt` ↔ `LazyColumn`
- Items provider must use `itemsIndexed(tracks, key = { index, track -> "pl_${playlistId}_${track.videoId}_$index" }, contentType = { _, _ -> "track_item" })`.
- Layout modifier must use `.fillMaxWidth().weight(1f)` inside parent Column.

### `SwipeToQueueBox.kt`
- Must not use `BoxWithConstraints`.
- Uses `Box(modifier = modifier.fillMaxWidth().onSizeChanged { size -> ... })`.

### `SkullBeatApp.kt` ↔ `Coil`
- Implements `coil.ImageLoaderFactory`.
- Provides `newImageLoader()` with:
  - Memory cache: 25% of available RAM.
  - Disk cache: 50MB in cacheDir / "image_cache".
  - `respectCacheHeaders(false)`.

### `PlaylistDetailScreen.kt` ↔ Photo Selection
- `PlaylistCover` has clear visual edit badge and click handler triggering Photo Picker / Edit dialog.
- Selected image URI processed via `ImageUtils.processAndSavePlaylistCover` and stored to `Playlist.coverPath`.

## Code Layout
- `android/app/src/main/java/com/skullbeat/app/ui/screens/playlist/PlaylistDetailScreen.kt` — Playlist screen layout & LazyColumn
- `android/app/src/main/java/com/skullbeat/app/ui/components/TrackListItem.kt` — Track item rendering & thumbnail image loading
- `android/app/src/main/java/com/skullbeat/app/ui/components/SwipeToQueueBox.kt` — Gesture queue box layout
- `android/app/src/main/java/com/skullbeat/app/ui/components/PlaylistCover.kt` — Cover image component
- `android/app/src/main/java/com/skullbeat/app/SkullBeatApp.kt` — Application class & Coil configuration
- `android/app/src/main/java/com/skullbeat/app/domain/util/ImageUtils.kt` — Image processing & persistence
- `android/app/src/main/java/com/skullbeat/app/ui/screens/playlist/PlaylistViewModel.kt` — ViewModel for playlist state
- `android/app/src/main/java/com/skullbeat/app/ui/dialogs/CreateEditPlaylistDialog.kt` — Playlist dialog
- `android/app/src/test/java/com/skullbeat/app/` — Unit test suite
