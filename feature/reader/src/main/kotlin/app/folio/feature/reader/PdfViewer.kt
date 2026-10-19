package app.folio.feature.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.folio.core.data.modules.InstallState
import app.folio.core.designsystem.components.CircleButton
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MAX_ZOOM = 5f

/**
 * Fixed-page viewer for PDFs: horizontal paging, pinch and double-tap zoom, tap for controls.
 * Reflow settings (fonts, margins, themes, guide) don't apply to fixed pages, so none are offered.
 */
@Composable
internal fun PdfViewer(vm: PdfViewModel, kind: ReaderKind, onClose: () -> Unit) {
    var chrome by rememberSaveable { mutableStateOf(false) }
    ImmersiveMode(showBars = chrome, lightBars = false)
    BackHandler(enabled = chrome) { chrome = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (kind !is ReaderKind.Pdf) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val djvu = kind is ReaderKind.NeedsModule
                Text(
                    stringResource(if (djvu) R.string.djvu_needs_module else R.string.pdf_broken), color = Color.White,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp),
                )
                // DjVu opens only in the module: offer it right here.
                if (djvu) ModuleOffer(vm, page = { 0 }, onClose = onClose)
            }
            Box(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) {
                CircleButton(Icons.Rounded.Close, stringResource(R.string.reader_close), onClose)
            }
            return@Box
        }
        val pager = rememberPagerState(initialPage = kind.startPage) { kind.pageCount }
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { vm.onPage(it, kind.pageCount) } }
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1, key = { it }) { index ->
            PdfPage(vm, index, active = index == pager.currentPage, onZoomed = { zoomed = it }, onTap = { chrome = !chrome })
        }
        val scope = rememberCoroutineScope()
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        kind.title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    CircleButton(Icons.Rounded.Close, stringResource(R.string.reader_close), onClose)
                }
                Column(
                    Modifier.fillMaxWidth().background(Color(0xCC1C1C1E), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ModuleOffer(vm, page = { pager.currentPage }, onClose = onClose)
                    var drag by remember { mutableStateOf<Float?>(null) }
                    val shown = drag?.roundToInt() ?: pager.currentPage
                    Text(stringResource(R.string.pdf_page, shown + 1, kind.pageCount), color = Color.White, fontSize = 13.sp)
                    if (kind.pageCount > 1) {
                        Slider(
                            value = drag ?: pager.currentPage.toFloat(),
                            onValueChange = { drag = it },
                            onValueChangeFinished = {
                                drag?.let { target -> scope.launch { pager.scrollToPage(target.roundToInt()) } }
                                drag = null
                            },
                            valueRange = 0f..(kind.pageCount - 1).toFloat(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(vm: PdfViewModel, index: Int, active: Boolean, onZoomed: (Boolean) -> Unit, onTap: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val height = constraints.maxHeight.toFloat()
        // Twice the screen width keeps text sharp up to ~2× zoom without a second render.
        val renderWidth = (width * 2).roundToInt().coerceIn(1, 2560)
        val bitmap by produceState<Bitmap?>(null, index, renderWidth) { value = vm.render(index, renderWidth) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
        LaunchedEffect(scale, active) { if (active) onZoomed(scale > 1.01f) }
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = width * (s - 1) / 2
            val maxY = height * (s - 1) / 2
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { at ->
                            if (scale > 1.01f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = clamp((Offset(size.width / 2f, size.height / 2f) - at) * 1.5f, 2.5f)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // Pinch always zooms; a one-finger drag pans only while zoomed, otherwise the pager gets it.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2 || scale > 1.01f) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                val centroid = event.calculateCentroid() - Offset(size.width / 2f, size.height / 2f)
                                offset = clamp((offset - centroid) * (newScale / scale) + centroid + event.calculatePan(), newScale)
                                scale = newScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
                )
            }
        }
    }
}

/** «Folio Документы»: install it from here, then reopen the book in it at the current page. */
@Composable
private fun ModuleOffer(vm: PdfViewModel, page: () -> Int, onClose: () -> Unit) {
    val offer by vm.offer.collectAsStateWithLifecycle()
    val ready by vm.moduleReady.collectAsStateWithLifecycle()
    val states by vm.installStates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apk = offer
    if (apk == null && !ready) return
    val state = apk?.let { states[it.packageName] } ?: InstallState.Idle
    val action = when {
        ready -> stringResource(R.string.pdf_module_open)
        state is InstallState.Downloading -> state.fraction?.let { "${(it * 100).toInt()} %" } ?: stringResource(R.string.speech_module_downloading)
        state == InstallState.Installing -> stringResource(R.string.speech_module_installing)
        state == InstallState.NeedsPermission -> stringResource(R.string.speech_module_permission)
        state is InstallState.Failed -> stringResource(R.string.speech_voice_retry)
        else -> stringResource(R.string.speech_module_install)
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable {
            if (ready) {
                scope.launch {
                    vm.moduleIntent(page())?.let { runCatching { context.startActivity(it) } }
                    onClose()
                }
            } else if (apk != null && state !is InstallState.Downloading && state != InstallState.Installing) {
                vm.installModule(apk)
            }
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.pdf_module_offer), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(action, color = Color(0xFF0A84FF), fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
    }
}
