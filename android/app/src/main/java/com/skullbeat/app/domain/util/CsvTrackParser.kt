package com.skullbeat.app.domain.util

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object CsvTrackParser {

    data class CsvRow(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val durationSec: Int = 0,
        val videoId: String = "",
        val url: String = "",
        val playlist: String = ""
    )

    private val TITLE_HEADERS = setOf(
        "title", "track", "track name", "song", "song name", "song title", "name", "trackname", "songname"
    )

    private val ARTIST_HEADERS = setOf(
        "artist", "artists", "artist name", "artist name s", "artist s", "singer", "uploader", "author", "band", "artistnames", "channel"
    )

    private val ALBUM_HEADERS = setOf(
        "album", "album name", "album title", "record"
    )

    private val DURATION_HEADERS = setOf(
        "duration", "duration sec", "durationsec", "duration ms", "durationms", "time", "length"
    )

    private val VIDEO_ID_HEADERS = setOf(
        "videoid", "video id", "ytid", "youtube id", "id"
    )

    private val URL_HEADERS = setOf(
        "url", "link", "youtube url", "youtube link", "track url", "track link", "uri", "yt url", "yt link"
    )

    private val PLAYLIST_HEADERS = setOf(
        "playlist", "playlist name"
    )

    private val YT_ID_REGEX = Regex("""(?:v=|youtu\.be/|embed/|shorts/)([a-zA-Z0-9_-]{11})""")

    fun parse(inputStream: InputStream): List<CsvRow> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val lines = reader.readLines()
        return parseLines(lines)
    }

    fun parse(content: String): List<CsvRow> {
        return parseLines(content.lines())
    }

    fun parseLines(rawLines: List<String>): List<CsvRow> {
        if (rawLines.isEmpty()) return emptyList()

        val cleanLines = rawLines
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotBlank() }

        if (cleanLines.isEmpty()) return emptyList()

        val delimiter = detectDelimiter(cleanLines.first())
        val parsedRows = cleanLines.map { parseCsvLine(it, delimiter) }
        if (parsedRows.isEmpty()) return emptyList()

        val firstRow = parsedRows.first()
        val titleIdx = findHeaderIndex(firstRow, TITLE_HEADERS)
        val artistIdx = findHeaderIndex(firstRow, ARTIST_HEADERS)
        val albumIdx = findHeaderIndex(firstRow, ALBUM_HEADERS)
        val durationIdx = findHeaderIndex(firstRow, DURATION_HEADERS)
        val videoIdIdx = findHeaderIndex(firstRow, VIDEO_ID_HEADERS)
        val urlIdx = findHeaderIndex(firstRow, URL_HEADERS)
        val playlistIdx = findHeaderIndex(firstRow, PLAYLIST_HEADERS)

        val isHeaderPresent = titleIdx != -1

        val (startIndex, actualTitleIdx, actualArtistIdx) = if (isHeaderPresent) {
            val aIdx = if (artistIdx != -1 && artistIdx != titleIdx) artistIdx else if (firstRow.size > 1 && titleIdx != 1) 1 else -1
            Triple(1, titleIdx, aIdx)
        } else {
            Triple(0, 0, if (firstRow.size > 1) 1 else -1)
        }

        val results = mutableListOf<CsvRow>()
        for (i in startIndex until parsedRows.size) {
            val row = parsedRows[i]
            if (row.isEmpty()) continue

            val rawTitle = if (actualTitleIdx in row.indices) cleanField(row[actualTitleIdx]) else ""
            val rawArtist = if (actualArtistIdx != -1 && actualArtistIdx in row.indices) cleanField(row[actualArtistIdx]) else ""
            val rawAlbum = if (albumIdx != -1 && albumIdx in row.indices) cleanField(row[albumIdx]) else ""
            val rawDurationStr = if (durationIdx != -1 && durationIdx in row.indices) cleanField(row[durationIdx]) else ""
            val rawVideoId = if (videoIdIdx != -1 && videoIdIdx in row.indices) cleanField(row[videoIdIdx]) else ""
            val rawUrl = if (urlIdx != -1 && urlIdx in row.indices) cleanField(row[urlIdx]) else ""
            val rawPlaylist = if (playlistIdx != -1 && playlistIdx in row.indices) cleanField(row[playlistIdx]) else ""

            if (rawTitle.isBlank()) continue

            // Skip repeated header rows
            val normalizedTitle = normalizeHeader(rawTitle)
            if (TITLE_HEADERS.contains(normalizedTitle) || rawTitle.startsWith("spotify:track:", ignoreCase = true)) {
                continue
            }

            // Clean & format artist (Spotify exports use semicolon for multiple artists, e.g. "Kina;Snow")
            val cleanArtist = if (rawArtist.contains(";")) {
                rawArtist.split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(", ")
            } else {
                rawArtist
            }

            // Parse duration seconds
            val durSec = when {
                rawDurationStr.contains(":") -> {
                    val parts = rawDurationStr.split(":")
                    val m = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val s = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    m * 60 + s
                }
                rawDurationStr.toIntOrNull() != null -> {
                    val num = rawDurationStr.toInt()
                    if (num > 10000) num / 1000 else num
                }
                else -> 0
            }

            // Extract videoId if not directly supplied but present in URL
            val resolvedVideoId = when {
                rawVideoId.length == 11 && !rawVideoId.contains(" ") -> rawVideoId
                rawVideoId.isNotBlank() && !rawVideoId.startsWith("http") -> rawVideoId
                rawUrl.isNotBlank() -> YT_ID_REGEX.find(rawUrl)?.groupValues?.get(1).orEmpty()
                else -> ""
            }

            results.add(
                CsvRow(
                    title = rawTitle,
                    artist = cleanArtist,
                    album = rawAlbum,
                    durationSec = durSec,
                    videoId = resolvedVideoId,
                    url = rawUrl,
                    playlist = rawPlaylist
                )
            )
        }
        return results
    }

    private fun cleanField(field: String): String {
        return field.trim().removeSurrounding("\"").trim()
    }

    private fun normalizeHeader(text: String): String {
        return text.lowercase()
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun detectDelimiter(line: String): Char {
        var commas = 0
        var tabs = 0
        var semicolons = 0
        var inQuotes = false

        for (c in line) {
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes) {
                when (c) {
                    ',' -> commas++
                    '\t' -> tabs++
                    ';' -> semicolons++
                }
            }
        }

        return when {
            commas > 0 -> ','
            tabs > 0 -> '\t'
            semicolons > 0 -> ';'
            else -> ','
        }
    }

    private fun findHeaderIndex(row: List<String>, headerSet: Set<String>): Int {
        for (i in row.indices) {
            val colClean = normalizeHeader(row[i])
            if (headerSet.contains(colClean)) {
                return i
            }
        }
        return -1
    }

    fun parseCsvLine(line: String, delimiter: Char = ','): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++ // Skip escaped quote
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == delimiter && !inQuotes -> {
                    tokens.add(sb.toString().trim())
                    sb.clear()
                }
                else -> {
                    sb.append(c)
                }
            }
            i++
        }
        tokens.add(sb.toString().trim())
        return tokens
    }
}
