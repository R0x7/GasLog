package com.gaslog.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

class PreparedPhoto(val base64: String, val thumb: Bitmap)

object PhotoUtils {
    /** A fresh file the camera app can write into. Old shots (over a day) are cleaned up. */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { now - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val file = File(dir, "shot_$now.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Shrinks to [maxEdge] px on the long side, fixes rotation, and JPEG-encodes for Gemini. Blocking. */
    fun prepare(context: Context, uri: Uri, maxEdge: Int = 1800): PreparedPhoto {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Couldn't open that photo.")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp: Bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Couldn't open that photo.")

        val scale = maxEdge.toFloat() / max(bmp.width, bmp.height)
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true)
        }

        val degrees = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)

        val t = 360f / max(bmp.width, bmp.height)
        val thumb = if (t < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * t).roundToInt(), (bmp.height * t).roundToInt(), true)
        } else bmp

        return PreparedPhoto(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), thumb)
    }

    /** A frame grabbed from the live camera preview, prepared like a photo. */
    fun fromBitmap(src: Bitmap, maxEdge: Int = 1800): PreparedPhoto {
        val s = maxEdge.toFloat() / max(src.width, src.height)
        val bmp = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val t = 360f / max(bmp.width, bmp.height)
        val thumb = if (t < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * t).roundToInt(), (bmp.height * t).roundToInt(), true)
        } else bmp
        return PreparedPhoto(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), thumb)
    }
}
