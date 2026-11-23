package io.github.patschgoo.castapp.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.artwork.PhoneThumbnails
import io.github.patschgoo.castapp.ui.theme.LocalCastColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A video on this phone, as "Recent on this phone" shows it. */
class PhoneVideo(val id: Long, val uri: Uri, val title: String, val durationMs: Long)

/** Reads the phone's newest videos and their small pictures from Android's media library. */
private object PhoneVideos {
    private val thumbnails = LruCache<Long, ImageBitmap>(48)

    fun hasAccess(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        val granted = RecentRules.permissions(sdk)
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet()
        return RecentRules.hasAccess(sdk, granted)
    }

    /** Newest first. Empty without access, without videos, or when the media library can't be read. */
    suspend fun newest(context: Context, limit: Int = 12): List<PhoneVideo> = withContext(Dispatchers.IO) {
        try {
            val collection =
                if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.VideoColumns._ID,
                MediaStore.Video.VideoColumns.DISPLAY_NAME,
                MediaStore.Video.VideoColumns.DURATION,
            )
            val videos = ArrayList<PhoneVideo>()
            context.contentResolver.query(collection, projection, null, null, MediaStore.Video.VideoColumns.DATE_ADDED + " DESC")?.use { c ->
                val idColumn = c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns._ID)
                val nameColumn = c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DISPLAY_NAME)
                val durationColumn = c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                while (videos.size < limit && c.moveToNext()) {
                    val id = c.getLong(idColumn)
                    videos += PhoneVideo(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(nameColumn).orEmpty(),
                        durationMs = c.getLong(durationColumn),
                    )
                }
            }
            videos
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun thumbnail(context: Context, video: PhoneVideo, widthPx: Int, heightPx: Int): ImageBitmap? {
        thumbnails.get(video.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                // Shared with "Continue watching" ([io.github.patschgoo.castapp.artwork.Artwork]).
                PhoneThumbnails.load(context, video.uri, widthPx, heightPx)?.asImageBitmap()?.also { thumbnails.put(video.id, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * "Recent on this phone": the newest videos with their pictures; a tap plays one on the TV.
 * Listing videos needs Android's permission for them, so without it the row is a small card
 * that offers to turn it on ([onHide] turns the row off for good; Settings brings it back).
 * Nothing is shown when the phone has no videos.
 */
@Composable
internal fun RecentVideosRow(onPlay: (PhoneVideo) -> Unit, onSeeAll: () -> Unit, onHide: () -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(PhoneVideos.hasAccess(context)) }
    var videos by remember { mutableStateOf<List<PhoneVideo>>(emptyList()) }
    var lookAgain by remember { mutableIntStateOf(0) }
    // Back from the camera, or from the system settings: the videos or the permission may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = PhoneVideos.hasAccess(context)
        lookAgain++
    }
    LaunchedEffect(access, lookAgain) { videos = if (access) PhoneVideos.newest(context) else emptyList() }

    var askedAt by remember { mutableLongStateOf(0L) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = PhoneVideos.hasAccess(context)
        // Answered at once without a dialog: Android was told not to ask again. The switch is in the system settings.
        if (!access && SystemClock.elapsedRealtime() - askedAt < 400) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    when {
        !access -> Surface(shape = RoundedCornerShape(20.dp), color = LocalCastColors.current.card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                Text(
                    stringResource(R.string.home_recent_ask_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.home_recent_ask_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        askedAt = SystemClock.elapsedRealtime()
                        permission.launch(RecentRules.permissions(Build.VERSION.SDK_INT))
                    }) { Text(stringResource(R.string.home_recent_allow)) }
                    TextButton(onClick = onHide) { Text(stringResource(R.string.home_recent_hide)) }
                }
            }
        }
        videos.isNotEmpty() -> Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_recent_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onSeeAll) { Text(stringResource(R.string.home_recent_all)) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(videos, key = { it.id }) { video -> RecentCard(video, onClick = { onPlay(video) }) }
            }
        }
    }
}

@Composable
private fun RecentCard(video: PhoneVideo, onClick: () -> Unit) {
    val context = LocalContext.current
    val (widthPx, heightPx) = with(LocalDensity.current) { 140.dp.roundToPx() to 80.dp.roundToPx() }
    val picture by produceState<ImageBitmap?>(null, video.id) {
        value = PhoneVideos.thumbnail(context, video, widthPx, heightPx)
    }
    Column(
        Modifier.width(140.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = stringResource(R.string.home_recent_play), role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier.size(140.dp, 80.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val image = picture
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (video.durationMs > 0) {
                Text(
                    formatTime(video.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(video.title, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
