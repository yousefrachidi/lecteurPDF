package com.example.lecteurpdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Info

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewer(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var isDarkMode by remember { mutableStateOf(false) }
    var isHorizontal by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(0) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpPageInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("Aller à la page") },
            text = {
                OutlinedTextField(
                    value = jumpPageInput,
                    onValueChange = { if (it.all { char -> char.isDigit() }) jumpPageInput = it },
                    label = { Text("Page (1 - $pageCount)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val page = jumpPageInput.toIntOrNull()?.minus(1)
                    if (page != null && page in 0 until pageCount) {
                        scope.launch {
                            listState.scrollToItem(page)
                            currentPage = page
                        }
                    }
                    showJumpDialog = false
                    jumpPageInput = ""
                }) { Text("Aller") }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) { Text("Annuler") }
            }
        )
    }

    // Update current page based on scroll position
    LaunchedEffect(listState.firstVisibleItemIndex) {
        currentPage = listState.firstVisibleItemIndex
    }

    val colorMatrix = remember(isDarkMode) {
        if (isDarkMode) {
            ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
        } else {
            ColorMatrix()
        }
    }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                if (fileDescriptor != null) {
                    val pdfRenderer = PdfRenderer(fileDescriptor)
                    renderer = pdfRenderer
                    pageCount = pdfRenderer.pageCount
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column(modifier = Modifier.clickable { if (pageCount > 0) showJumpDialog = true }) {
                        Text("Visualiseur PDF", style = MaterialTheme.typography.titleMedium)
                        if (pageCount > 0) {
                            Text(
                                "Page ${currentPage + 1} / $pageCount",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { isHorizontal = !isHorizontal }) {
                        Icon(
                            if (isHorizontal) Icons.Default.SwapVert else Icons.Default.SwapHoriz,
                            contentDescription = "Changer direction"
                        )
                    }
                    IconButton(onClick = { isDarkMode = !isDarkMode }) {
                        Icon(
                            if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Mode Nuit"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (pageCount > 1) {
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Slider(
                            value = currentPage.toFloat(),
                            onValueChange = { 
                                currentPage = it.toInt()
                            },
                            onValueChangeFinished = {
                                // Scroll to the selected page
                                // Using scope to launch scroll
                            },
                            valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                            steps = (pageCount - 2).coerceAtLeast(0)
                        )
                        // Using a LaunchedEffect to trigger the scroll when currentPage changes from Slider
                    }
                }
            }
        }
    ) { padding ->
        // Handle jump to page from slider
        LaunchedEffect(currentPage) {
            if (!listState.isScrollInProgress) {
                listState.scrollToItem(currentPage)
            }
        }

        if (renderer != null) {
            if (isHorizontal) {
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(if (isDarkMode) Color.Black else Color.Gray),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(pageCount) { index ->
                        PdfPage(renderer!!, index, colorMatrix, isHorizontal = true)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(if (isDarkMode) Color.Black else Color.Gray),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(pageCount) { index ->
                        PdfPage(renderer!!, index, colorMatrix, isHorizontal = false)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    DisposableEffect(uri) {
        onDispose {
            renderer?.close()
        }
    }
}

@Composable
fun PdfPage(renderer: PdfRenderer, pageIndex: Int, colorMatrix: ColorMatrix, isHorizontal: Boolean) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale *= zoomChange
        offset += offsetChange
    }

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                val page = renderer.openPage(pageIndex)
                // Render at higher quality (2x)
                val newBitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                page.render(newBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap = newBitmap
                page.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Card(
        modifier = (if (isHorizontal) Modifier.fillMaxHeight().aspectRatio(if (bitmap != null) bitmap!!.width.toFloat() / bitmap!!.height.toFloat() else 0.7f)
                  else Modifier.fillMaxWidth())
                  .graphicsLayer(
                       scaleX = maxOf(1f, scale),
                       scaleY = maxOf(1f, scale),
                       translationX = offset.x,
                       translationY = offset.y
                   )
                   .transformable(state = state)
                   .pointerInput(Unit) {
                       detectTapGestures(
                           onDoubleTap = {
                               scale = 1f
                               offset = Offset.Zero
                           }
                       )
                   },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.colorMatrix(colorMatrix)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!isHorizontal) Modifier.height(400.dp) else Modifier.width(300.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
