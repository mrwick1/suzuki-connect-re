package dev.mrwick.redline.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a captured [ImageBitmap] (from [SceneCapture.capture]) to a cache PNG and
 * fires an ACTION_SEND chooser.
 *
 * Uses the app's `${packageName}.fileprovider` authority (already declared in
 * AndroidManifest for ride / GPX sharing). Writes to [Context.getCacheDir]/wrapped/,
 * which is covered by the `<cache-path name="exports" path="." />` entry in
 * file_provider_paths.xml (path="." maps the entire cacheDir tree).
 *
 * NOTE: Call on a background dispatcher — bitmap compression + file I/O are blocking.
 * The caller is responsible for running this off the main thread (e.g. via
 * `withContext(Dispatchers.IO) { ... }`).
 *
 * @param context    Used for cacheDir, package name, and startActivity.
 * @param image      The captured scene bitmap from SceneCapture.capture().
 * @param fileName   PNG filename, e.g. "wrapped-this_year.png".
 * @param chooserTitle Title string shown in the OS share chooser.
 */
object BitmapShare {

    /**
     * Compress [image] to a PNG in cacheDir/wrapped/ and fire an ACTION_SEND intent.
     *
     * FileProvider authority: `${context.packageName}.fileprovider`
     * FileProvider-covered dir used: cacheDir/wrapped/ (covered by `<cache-path path="." />`)
     */
    fun shareImageBitmap(
        context: Context,
        image: ImageBitmap,
        fileName: String,
        chooserTitle: String,
    ) {
        val bmp = image.asAndroidBitmap()
        val dir = File(context.cacheDir, "wrapped").also { it.mkdirs() }
        val png = File(dir, fileName)
        png.outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            png,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
