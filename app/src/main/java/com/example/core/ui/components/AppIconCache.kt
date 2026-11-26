package com.example.core.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide LRU bitmap cache for installed app icons loaded asynchronously on Dispatchers.IO.
 * Eliminates synchronous main-thread PackageManager.getApplicationIcon() / toBitmap() stalls during scrolling!
 */
object AppIconCache {
    private val cache = object : LruCache<String, ImageBitmap>(180) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String, sizePx: Int = 96): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(packageName)?.let { return@withContext it }
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bmp = drawable.toBitmap(width = sizePx, height = sizePx).asImageBitmap()
                cache.put(packageName, bmp)
                bmp
            }.getOrNull()
        }
    }
}

@Composable
fun CachedAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val context = LocalContext.current
    val bitmap by produceState(
        initialValue = AppIconCache.get(packageName),
        key1 = packageName,
    ) {
        if (value == null) {
            value = AppIconCache.load(context, packageName)
        }
    }

    val shape = RoundedCornerShape(10.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(size * 0.56f),
            )
        }
    }
}
