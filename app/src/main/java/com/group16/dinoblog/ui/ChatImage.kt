package com.group16.dinoblog.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decode only a small chat thumbnail; never decode the full DTN image on recomposition. */
@Composable
fun ChatImage(path: String, description: String, modifier: Modifier = Modifier) {
    val modified = File(path).lastModified()
    val bitmap by produceState<android.graphics.Bitmap?>(null, path, modified) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    Box(modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(requireNotNull(bitmap).asImageBitmap(), description,
            Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Crop)
        else Text("Image unavailable")
    }
}
