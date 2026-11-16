package com.skullbeat.app.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    private const val TAG = "ImageUtils"
    private const val TARGET_SIZE = 512

    suspend fun processAndSavePlaylistCover(
        context: Context,
        sourceUri: Uri,
        playlistId: Long,
        oldCoverPath: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val tempFile = try {
            File.createTempFile("cover_raw_${playlistId}_", ".tmp", cacheDir)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create temp file for cover processing", e)
            return@withContext null
        }

        try {
            // 1. Copy source stream to local temp file ONCE to preserve stream and avoid permission expiration
            var bytesCopied = 0L
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        bytesCopied = input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy image from URI: $sourceUri", e)
            }

            if (bytesCopied <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
                Log.e(TAG, "Source image is empty or inaccessible (bytes: $bytesCopied)")
                return@withContext null
            }

            // 2. Decode bitmap with modern ImageDecoder (API 28+) or robust BitmapFactory fallback
            var decodedBitmap: Bitmap? = null
            var exifHandled = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val source = ImageDecoder.createSource(tempFile)
                    decodedBitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true

                        // Downsample if very large to prevent OOM
                        val maxDim = max(info.size.width, info.size.height)
                        if (maxDim > TARGET_SIZE * 2) {
                            val sample = (maxDim / TARGET_SIZE).coerceAtLeast(1)
                            decoder.setTargetSampleSize(sample)
                        }
                    }
                    // ImageDecoder automatically applies EXIF orientation!
                    exifHandled = true
                } catch (e: Throwable) {
                    Log.w(TAG, "ImageDecoder failed, attempting BitmapFactory fallback", e)
                }
            }

            if (decodedBitmap == null) {
                // BitmapFactory fallback using local file
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(tempFile.absolutePath, boundsOptions)

                val origW = boundsOptions.outWidth
                val origH = boundsOptions.outHeight
                if (origW <= 0 || origH <= 0) {
                    Log.e(TAG, "BitmapFactory could not read bounds from temp file")
                    return@withContext null
                }

                var inSampleSize = 1
                val minDim = min(origW, origH)
                while ((minDim / inSampleSize) > TARGET_SIZE * 2) {
                    inSampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                decodedBitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
            }

            val validBitmap = decodedBitmap ?: run {
                Log.e(TAG, "All decoding strategies failed for $sourceUri")
                return@withContext null
            }

            // 3. Fix EXIF orientation if not already handled by ImageDecoder
            var orientedBitmap: Bitmap = validBitmap
            if (!exifHandled) {
                try {
                    val exif = ExifInterface(tempFile.absolutePath)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    val rotationDegrees = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                    if (rotationDegrees != 0) {
                        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        val rotated = Bitmap.createBitmap(
                            validBitmap, 0, 0,
                            validBitmap.width, validBitmap.height,
                            matrix, true
                        )
                        if (rotated != validBitmap) {
                            validBitmap.recycle()
                            orientedBitmap = rotated
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "EXIF rotation check failed", e)
                }
            }

            // 4. Center-crop to 1:1 square
            val squareDim = min(orientedBitmap.width, orientedBitmap.height)
            val cropX = (orientedBitmap.width - squareDim) / 2
            val cropY = (orientedBitmap.height - squareDim) / 2
            val croppedBitmap = Bitmap.createBitmap(
                orientedBitmap,
                cropX, cropY,
                squareDim, squareDim
            ).also {
                if (it != orientedBitmap) orientedBitmap.recycle()
            }

            // 5. Scale to TARGET_SIZE x TARGET_SIZE (512x512)
            val finalBitmap = if (croppedBitmap.width != TARGET_SIZE || croppedBitmap.height != TARGET_SIZE) {
                Bitmap.createScaledBitmap(croppedBitmap, TARGET_SIZE, TARGET_SIZE, true).also {
                    if (it != croppedBitmap) croppedBitmap.recycle()
                }
            } else {
                croppedBitmap
            }

            // 6. Save to internal filesDir/playlist_covers/pl_{playlistId}_{timestamp}.webp
            val coverDir = File(context.filesDir, "playlist_covers")
            if (!coverDir.exists()) coverDir.mkdirs()

            val newFile = File(coverDir, "pl_${playlistId}_${System.currentTimeMillis()}.webp")
            var writeSuccess = false
            FileOutputStream(newFile).use { fos ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                writeSuccess = finalBitmap.compress(format, 85, fos)
                if (!writeSuccess) {
                    // Fallback to JPEG if WebP compression fails on this hardware
                    writeSuccess = finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                }
                fos.flush()
            }
            finalBitmap.recycle()

            if (!writeSuccess || !newFile.exists() || newFile.length() <= 0L) {
                Log.e(TAG, "Failed to write compressed cover file to ${newFile.absolutePath}")
                return@withContext null
            }

            // 7. Delete old cover file if it exists and differs
            if (!oldCoverPath.isNullOrBlank() && oldCoverPath != newFile.absolutePath) {
                deleteCoverFile(oldCoverPath)
            }

            Log.d(TAG, "Successfully processed and saved playlist cover: ${newFile.absolutePath}")
            newFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error processing cover photo", e)
            null
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {}
        }
    }

    fun deleteCoverFile(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
    }

    fun createTempCameraUri(context: Context): Pair<Uri, File>? {
        return try {
            val cacheDir = File(context.cacheDir, "camera_temp")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val tempFile = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )
            Pair(uri, tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
