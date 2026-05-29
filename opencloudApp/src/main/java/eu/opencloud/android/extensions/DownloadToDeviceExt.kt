/*
 * openCloud Android client application
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package eu.opencloud.android.extensions

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import eu.opencloud.android.domain.files.model.OCFile
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Copy a locally available [OCFile] to the device's public Downloads folder.
 *
 * On API 29+ (Q) [MediaStore.Downloads] is used so no runtime permission is required.
 * On older versions the file is copied directly to the public Downloads directory
 * using the WRITE_EXTERNAL_STORAGE permission already declared in the manifest.
 *
 * @return true on success, false otherwise.
 */
fun Context.copyOCFileToPublicDownloads(file: OCFile): Boolean {
    val storagePath = file.storagePath
    if (storagePath.isNullOrEmpty()) {
        Timber.w("Cannot download ${file.fileName} to device: file has no local storage path")
        return false
    }
    val sourceFile = File(storagePath)
    if (!sourceFile.exists()) {
        Timber.w("Cannot download ${file.fileName} to device: local file does not exist")
        return false
    }

    val displayName = file.fileName
    val mimeType = file.mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        copyToDownloadsViaMediaStore(sourceFile, displayName, mimeType)
    } else {
        @Suppress("DEPRECATION")
        copyToDownloadsLegacy(sourceFile, displayName)
    }
}

private fun Context.copyToDownloadsViaMediaStore(
    sourceFile: File,
    displayName: String,
    mimeType: String,
): Boolean {
    val resolver = contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val downloadUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        ?: return false

    return try {
        resolver.openOutputStream(downloadUri)?.use { outputStream ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(outputStream)
            }
        } ?: return false

        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(downloadUri, contentValues, null, null)
        true
    } catch (e: IOException) {
        Timber.e(e, "Failed to copy file to Downloads via MediaStore")
        resolver.delete(downloadUri, null, null)
        false
    }
}

@Suppress("DEPRECATION")
private fun copyToDownloadsLegacy(sourceFile: File, displayName: String): Boolean {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (downloadsDir == null || (!downloadsDir.exists() && !downloadsDir.mkdirs())) {
        Timber.w("Downloads folder does not exist and could not be created")
        return false
    }
    val destination = uniqueFileIn(downloadsDir, displayName)
    return try {
        sourceFile.inputStream().use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        true
    } catch (e: IOException) {
        Timber.e(e, "Failed to copy file to Downloads folder")
        false
    }
}

private fun uniqueFileIn(directory: File, fileName: String): File {
    val candidate = File(directory, fileName)
    if (!candidate.exists()) return candidate
    val dotIndex = fileName.lastIndexOf('.')
    val base = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    var index = 1
    while (true) {
        val next = File(directory, "$base ($index)$extension")
        if (!next.exists()) return next
        index++
    }
}
