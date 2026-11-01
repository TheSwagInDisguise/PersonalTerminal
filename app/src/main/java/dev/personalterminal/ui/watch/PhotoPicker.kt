package dev.personalterminal.ui.watch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * State holder for "pick a photo" flows. The chosen image is copied (downscaled) into the app's
 * private photo store immediately, so [photoPath] is always a *relative* name inside
 * `watch_photos/` that can be persisted as-is.
 */
class PhotoPickerState internal constructor(
    initialPath: String?,
    private val prefix: String,
) {
    var photoPath by mutableStateOf(initialPath)
        internal set
    /** Bumped whenever the file behind [photoPath] changes so image caches refresh. */
    var version by mutableIntStateOf(0)
        internal set
    var showCamera by mutableStateOf(false)
        internal set
    /** Watch name stamped into the EXIF description of camera captures (3.9); null = no tag. */
    var tag: String? = null
    var error by mutableStateOf<String?>(null)
        internal set
    /** Name reserved for the next capture/import (stable across recompositions of the camera). */
    internal var pendingName: String? = null

    fun clear() { photoPath = null; version++ }
    internal fun newName(app: PersonalTerminalApp): String = app.watches.newPhotoName(prefix).also { pendingName = it }
}

@Composable
fun rememberPhotoPickerState(initialPath: String?, prefix: String = "wrist"): PhotoPickerState {
    // Survive configuration changes (rotation while the system photo picker is open is common).
    val saved = rememberSaveable { mutableStateOf(initialPath) }
    val state = remember { PhotoPickerState(saved.value, prefix) }
    saved.value = state.photoPath
    return state
}

/** The three ways to obtain a photo, as plain callbacks so any UI (row, dialog…) can trigger them. */
class PhotoPickerActions internal constructor(
    val openCamera: () -> Unit,
    val pickFromPhotos: () -> Unit,
    val browseFiles: () -> Unit,
)

/**
 * Registers the activity-result launchers for [state]: the system photo picker (recent photos, no
 * permission needed) and a general "browse files" document picker for images outside the media
 * library (downloads, cloud drives). Every path ends with the image imported into the app's
 * private photo store; the in-app camera is hosted by [photoPickerCamera].
 */
@Composable
fun rememberPhotoPickerActions(app: PersonalTerminalApp, state: PhotoPickerState): PhotoPickerActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun import(uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch {
            val name = state.newName(app)
            runCatching { withContext(Dispatchers.IO) { importPhoto(ctx, uri, app.watches.photoFile(name)) } }
                .onSuccess { state.photoPath = name; state.version++; state.error = null }
                .onFailure { state.error = "import failed: ${it.message ?: it.javaClass.simpleName}" }
        }
    }
    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { import(it) }
    // Bytes are copied immediately, so the one-shot read grant from the picker is all we need.
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { import(it) }
    return remember(state) {
        PhotoPickerActions(
            openCamera = { state.showCamera = true },
            pickFromPhotos = { pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            browseFiles = { openDocument.launch(arrayOf("image/*")) },
        )
    }
}

/**
 * Thumbnail + source buttons. The caller renders [photoPickerCamera] (full-screen) when
 * [PhotoPickerState.showCamera] is set.
 */
@Composable
fun PhotoPickerRow(
    app: PersonalTerminalApp,
    state: PhotoPickerState,
    modifier: Modifier = Modifier,
    thumbSize: Dp = 96.dp,
    placeholder: String = "📷",
    placeholderColor: Color = Term.palette.fgDim,
    showRemove: Boolean = true,
    actions: PhotoPickerActions = rememberPhotoPickerActions(app, state),
) {
    val p = Term.palette
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(thumbSize).background(p.bgHighlight, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                val path = state.photoPath
                if (path != null) PhotoImage(app.watches.photoFile(path), state.version, Modifier.fillMaxSize())
                else Text(placeholder, color = placeholderColor, style = MaterialTheme.typography.displaySmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TermButton("● camera", color = p.cyan, onClick = actions.openCamera)
                TermButton("photos", color = p.fgDim, onClick = actions.pickFromPhotos)
                TermButton("browse files", color = p.fgDim, onClick = actions.browseFiles)
                if (showRemove && state.photoPath != null) TermButton("remove", color = p.red, onClick = { state.clear() })
            }
        }
        state.error?.let { Comment(it, color = p.red) }
    }
}

/** Terminal-styled "where from?" chooser used when a photo is requested from a list row. */
@Composable
fun PhotoSourceDialog(title: String, actions: PhotoPickerActions, onDismiss: () -> Unit) {
    val p = Term.palette
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(p.bgAlt, RoundedCornerShape(8.dp))
                .border(1.dp, p.border, RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${Term.prompt} $ $title", color = p.green, style = MaterialTheme.typography.bodyMedium)
            Comment("source:")
            TermButton("● camera", color = p.cyan, modifier = Modifier.fillMaxWidth(), onClick = { onDismiss(); actions.openCamera() })
            TermButton("photos", color = p.fg, modifier = Modifier.fillMaxWidth(), onClick = { onDismiss(); actions.pickFromPhotos() })
            TermButton("browse files", color = p.fg, modifier = Modifier.fillMaxWidth(), onClick = { onDismiss(); actions.browseFiles() })
            TermButton("cancel", color = p.fgDim, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

/** Renders a photo from the private store; [version] busts Coil's cache after the file is rewritten. */
@Composable
fun PhotoImage(file: File, version: Int, modifier: Modifier = Modifier) {
    AsyncImage(
        // A `file://` URI with a cache-busting query keeps Coil's key unique per version while
        // still resolving to a plain File on disk.
        model = "file://${file.absolutePath}?v=$version",
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * Hosts the full-screen camera when requested. Returns `true` when the camera is showing so the
 * caller can `return` early from its own layout.
 */
@Composable
fun photoPickerCamera(app: PersonalTerminalApp, state: PhotoPickerState): Boolean {
    if (!state.showCamera) return false
    val name = remember { state.newName(app) }
    CameraCapture(
        outputFile = app.watches.photoFile(name),
        onCaptured = { state.photoPath = name; state.version++; state.error = null; state.showCamera = false },
        onCancel = { state.showCamera = false },
        tag = state.tag,
    )
    return true
}
