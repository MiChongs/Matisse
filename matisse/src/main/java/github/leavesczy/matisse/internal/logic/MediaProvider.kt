package github.leavesczy.matisse.internal.logic

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import github.leavesczy.matisse.MediaFilter
import github.leavesczy.matisse.MediaResource
import github.leavesczy.matisse.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @Author: leavesCZY
 * @Date: 2022/6/2 11:11
 * @Desc:
 */
internal object MediaProvider {

    suspend fun createImage(
        context: Context,
        imageName: String,
        mimeType: String,
        relativePath: String
    ): Uri? {
        return withContext(context = Dispatchers.Default) {
            return@withContext try {
                val contentValues = ContentValues()
                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, imageName)
                contentValues.put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                context.contentResolver.insert(imageCollection, contentValues)
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun deleteMedia(context: Context, uri: Uri) {
        withContext(context = Dispatchers.Default) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun loadResources(
        context: Context,
        selection: String?,
        selectionArgs: Array<String>?,
        ignoreMedia: ((MediaResource) -> Boolean)?
    ): List<MediaResource>? {
        return withContext(context = Dispatchers.Default) {
            val idColumn = MediaStore.MediaColumns._ID
            val dataColumn = MediaStore.MediaColumns.DATA
            val sizeColumn = MediaStore.MediaColumns.SIZE
            val displayNameColumn = MediaStore.MediaColumns.DISPLAY_NAME
            val mineTypeColumn = MediaStore.MediaColumns.MIME_TYPE
            val bucketIdColumn = MediaStore.MediaColumns.BUCKET_ID
            val bucketDisplayNameColumn = MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            val dateModifiedColumn = MediaStore.MediaColumns.DATE_MODIFIED
            val projection = arrayOf(
                idColumn,
                dataColumn,
                sizeColumn,
                displayNameColumn,
                mineTypeColumn,
                bucketIdColumn,
                bucketDisplayNameColumn
            )
            val contentUri = MediaStore.Files.getContentUri("external")
            val sortOrder = "$dateModifiedColumn DESC"
            val mediaResourceList = mutableListOf<MediaResource>()
            try {
                val mediaCursor = context.contentResolver.query(
                    contentUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder,
                ) ?: return@withContext null
                mediaCursor.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn, Long.MAX_VALUE)
                        val data = cursor.getString(dataColumn, "")
                        val size = cursor.getLong(sizeColumn, 0)
                        if (id == Long.MAX_VALUE || data.isBlank() || size <= 0) {
                            continue
                        }
                        val file = File(data)
                        if (!file.exists() || !file.isFile) {
                            continue
                        }
                        val name = cursor.getString(displayNameColumn, "")
                        val mimeType = cursor.getString(mineTypeColumn, "")
                        val bucketId = cursor.getString(bucketIdColumn, "")
                        val bucketName = cursor.getString(bucketDisplayNameColumn, "")
                        val uri = ContentUris.withAppendedId(contentUri, id)
                        val mediaResource = MediaResource(
                            id = id,
                            path = data,
                            uri = uri,
                            name = name,
                            mimeType = mimeType,
                            bucketId = bucketId,
                            bucketName = bucketName
                        )
                        if (ignoreMedia != null && ignoreMedia(mediaResource)) {
                            continue
                        }
                        mediaResourceList.add(element = mediaResource)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return@withContext mediaResourceList
        }
    }

    suspend fun loadResources(
        context: Context,
        mediaType: MediaType,
        mediaFilter: MediaFilter?
    ): List<MediaResource> {
        return withContext(context = Dispatchers.Default) {
            loadResources(
                context = context,
                selection = generateSqlSelection(mediaType = mediaType),
                selectionArgs = null,
                ignoreMedia = {
                    mediaFilter?.ignoreMedia(mediaResource = it) ?: false
                }
            ) ?: emptyList()
        }
    }

    private fun generateSqlSelection(mediaType: MediaType): String {
        val mediaTypeColumn = MediaStore.Files.FileColumns.MEDIA_TYPE
        val mediaTypeImageColumn = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val mediaTypeVideoColumn = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val mimeTypeColumn = MediaStore.Files.FileColumns.MIME_TYPE
        val queryImageSelection =
            "$mediaTypeColumn = $mediaTypeImageColumn and $mimeTypeColumn like 'image/%'"
        val queryVideoSelection =
            "$mediaTypeColumn = $mediaTypeVideoColumn and $mimeTypeColumn like 'video/%'"
        return when (mediaType) {
            is MediaType.MultipleMimeType -> {
                mediaType.mimeTypes.joinToString(
                    prefix = "$mimeTypeColumn in (",
                    postfix = ")",
                    separator = ",",
                    transform = {
                        "'${it}'"
                    }
                )
            }

            is MediaType.ImageOnly -> {
                queryImageSelection
            }

            MediaType.VideoOnly -> {
                queryVideoSelection
            }

            is MediaType.ImageAndVideo -> {
                buildString {
                    append(queryImageSelection)
                    append(" or ")
                    append(queryVideoSelection)
                }
            }
        }
    }

    suspend fun loadResources(context: Context, uri: Uri): MediaResource? {
        return withContext(context = Dispatchers.Default) {
            val id = ContentUris.parseId(uri)
            val selection = MediaStore.MediaColumns._ID + " = " + id
            val resources = loadResources(
                context = context,
                selection = selection,
                selectionArgs = null,
                ignoreMedia = null
            )
            if (resources.isNullOrEmpty() || resources.size != 1) {
                return@withContext null
            }
            return@withContext resources[0]
        }
    }

}

private fun Cursor.getLong(columnName: String, default: Long): Long {
    return try {
        val columnIndex = getColumnIndexOrThrow(columnName)
        getLong(columnIndex)
    } catch (e: Throwable) {
        e.printStackTrace()
        default
    }
}

private fun Cursor.getString(columnName: String, default: String): String {
    return try {
        val columnIndex = getColumnIndexOrThrow(columnName)
        getString(columnIndex) ?: default
    } catch (e: Throwable) {
        e.printStackTrace()
        default
    }
}