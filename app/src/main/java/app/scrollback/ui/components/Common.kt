package app.scrollback.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.db.isVideo
import app.scrollback.data.db.thumbModel
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace
import coil3.compose.AsyncImage
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.foundation.layout.fillMaxSize
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import kotlinx.coroutines.launch

/**
 * Thumbnail for a video (frame at 1s), picture or song. Remote items use the server preview;
 * [full] loads the original (full-screen viewer). Remote URLs are resolved at load time and cached
 * under their stable DB URI, so credentials never become cache keys.
 */
@Composable
fun MediaThumb(item: MediaEntity, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, full: Boolean = false) {
    val ctx = LocalContext.current
    val auth = app.scrollback.ui.LocalContainer.current.auth
    val model = if (full && !item.isVideo) (item.localUri ?: item.uri) else item.thumbModel
    val remoteVideoWithoutPreview = item.isVideo && item.thumbUri == null && item.localUri == null && auth.isRemote(item.uri)
    val request = remember(model, full) {
        ImageRequest.Builder(ctx)
            .data(auth.resolve(model))
            .memoryCacheKey(model + if (full) "#full" else "")
            .diskCacheKey(model)
            .apply { if (item.isVideo && model == item.uri) videoFrameMillis(1000) }
            .build()
    }
    Box(modifier.background(SbColors.Surface), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (item.kind == app.scrollback.data.db.MediaKind.AUDIO.id && item.thumbUri == null) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Outlined.MusicNote, null,
                tint = SbColors.TextTertiary, modifier = Modifier.fillMaxSize(0.42f),
            )
        } else if (!remoteVideoWithoutPreview) {
            AsyncImage(model = request, contentDescription = item.name, contentScale = contentScale, modifier = Modifier.matchParentSize())
        }
    }
}

object Fmt {
    fun size(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)
    fun duration(ms: Long): String {
        if (ms <= 0) return "—"
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
    fun date(ctx: Context, millis: Long): String =
        DateUtils.formatDateTime(ctx, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
    fun relative(millis: Long): String =
        DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

/** Opens the Android share sheet for a content URI, granting read access to the receiver. */
fun shareMedia(ctx: Context, uri: Uri, mime: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

/** Frosted bottom sheet (translucent, 28dp corners, light top border). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = SbColors.SurfaceHigh.copy(alpha = 0.94f),
        scrimColor = SbColors.Background.copy(alpha = 0.55f),
        shape = app.scrollback.ui.theme.SbRadius.Sheet,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = SbSpace.screenH).padding(bottom = SbSpace.l)) {
            content(close)
        }
    }
}

@Composable
fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = SbColors.TextPrimary, modifier = Modifier.padding(bottom = SbSpace.l))
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SbColors.SurfaceHigh,
        shape = app.scrollback.ui.theme.SbRadius.Large,
        title = { Text(title) },
        text = { Text(body, color = SbColors.TextSecondary) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) SbColors.Danger else SbColors.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SbColors.TextSecondary) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SbColors.SurfaceHigh,
        shape = app.scrollback.ui.theme.SbRadius.Large,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SbColors.TextSecondary) } },
    )
}

/** Asks for the PIN; [verify] returns true when correct. */
@Composable
fun PinPromptDialog(
    title: String,
    verify: suspend (String) -> Boolean,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SbColors.SurfaceHigh,
        shape = app.scrollback.ui.theme.SbRadius.Large,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8); error = false },
                singleLine = true,
                isError = error,
                supportingText = { if (error) Text("Wrong PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = {
                scope.launch {
                    if (verify(pin)) { haptics.confirm(); onSuccess(); onDismiss() } else { haptics.reject(); error = true; pin = "" }
                }
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SbColors.TextSecondary) } },
    )
}

@Composable
fun SetPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length in 4..8 && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SbColors.SurfaceHigh,
        shape = app.scrollback.ui.theme.SbRadius.Large,
        title = { Text("Set PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                Text("4–8 digits. It locks the app and hidden items.", color = SbColors.TextSecondary)
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    confirm, { confirm = it.filter(Char::isDigit).take(8) }, label = { Text("Confirm PIN") }, singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != pin,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSet(pin); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SbColors.TextSecondary) } },
    )
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextSecondary, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextPrimary, modifier = Modifier.weight(0.65f))
    }
}
