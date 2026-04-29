package com.example.lecteurpdf

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lecteurpdf.ui.theme.LecteurPDFTheme

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.History
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.print.PrintDocumentAdapter
import android.os.CancellationSignal
import android.print.PageRange
import android.print.PrintDocumentInfo
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import android.provider.Settings
import android.net.Uri

class PdfPrintAdapter(private val context: Context, private val uri: android.net.Uri, private val fileName: String) : PrintDocumentAdapter() {
    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(fileName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor?, cancellationSignal: CancellationSignal?, callback: WriteResultCallback?) {
        var input: InputStream? = null
        var output: OutputStream? = null
        try {
            input = context.contentResolver.openInputStream(uri)
            output = FileOutputStream(destination?.fileDescriptor)
            input?.copyTo(output)
            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback?.onWriteFailed(e.message)
        } finally {
            input?.close()
            output?.close()
        }
    }
}

fun printPdf(context: Context, pdf: PdfFile) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val jobName = "${context.getString(R.string.app_name)} - ${pdf.name}"
    printManager.print(jobName, PdfPrintAdapter(context, pdf.uri, pdf.name), null)
}

class MainActivity : ComponentActivity() {
    private val viewModel: PdfViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isAppDarkMode by viewModel.isAppDarkMode.collectAsState()
            LecteurPDFTheme(darkTheme = isAppDarkMode) {
                MainScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: PdfViewModel) {
    val context = LocalContext.current
    val pdfFiles by viewModel.pdfFiles.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val isAppDarkMode by viewModel.isAppDarkMode.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    
    var selectedPdf by remember { mutableStateOf<PdfFile?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSortMenu by remember { mutableStateOf(false) }
    var infoPdf by remember { mutableStateOf<PdfFile?>(null) }

    if (infoPdf != null) {
        val pageCount = remember(infoPdf) { viewModel.getPageCount(infoPdf!!) }
        AlertDialog(
            onDismissRequest = { infoPdf = null },
            title = { Text("Informations") },
            text = {
                Column {
                    Text("Nom: ${infoPdf!!.name}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Type: ${infoPdf!!.fileType.name}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Chemin: ${infoPdf!!.path}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Taille: ${formatFileSize(infoPdf!!.size)}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Dernière modification: ${formatDate(infoPdf!!.dateModified)}")
                    if (pageCount >= 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Nombre de pages: $pageCount")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { infoPdf = null }) { Text("Fermer") }
            }
        )
    }

    val filteredFiles = remember(pdfFiles, searchQuery, selectedTab) {
        val baseList = when (selectedTab) {
            0 -> pdfFiles
            1 -> {
                val recentPaths = viewModel.getRecentPaths()
                pdfFiles.filter { recentPaths.contains(it.path) }
            }
            2 -> pdfFiles.filter { it.isFavorite }
            else -> pdfFiles
        }
        
        if (searchQuery.isEmpty()) baseList
        else baseList.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                viewModel.scanFiles()
            } else {
                Toast.makeText(context, "Permission MANAGE_EXTERNAL_STORAGE refusée", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        val readImagesGranted = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        val readVideoGranted = permissions[Manifest.permission.READ_MEDIA_VIDEO] ?: false
        
        if (readGranted || (readImagesGranted && readVideoGranted)) {
            viewModel.scanFiles()
        } else {
            Toast.makeText(context, "Permission d'accès aux fichiers refusée", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                viewModel.scanFiles()
            } else {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }
            }
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            
            val allGranted = permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            
            if (allGranted) {
                viewModel.scanFiles()
            } else {
                permissionLauncher.launch(permissions)
            }
        }
    }

    if (selectedPdf != null) {
        when (selectedPdf!!.fileType) {
            FileType.PDF -> PdfViewer(uri = selectedPdf!!.uri, onBack = { selectedPdf = null })
            FileType.DOCX -> DocxViewer(uri = selectedPdf!!.uri, fileName = selectedPdf!!.name, onBack = { selectedPdf = null })
            FileType.XLSX -> XlsxViewer(uri = selectedPdf!!.uri, fileName = selectedPdf!!.name, onBack = { selectedPdf = null })
        }
    } else {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("Lecteur PDF Pro") },
                        actions = {
                            IconButton(onClick = { viewModel.toggleAppDarkMode() }) {
                                Icon(if (isAppDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "Mode Nuit")
                            }
                            IconButton(onClick = { viewModel.toggleGridView() }) {
                                Icon(if (isGridView) Icons.Default.ViewList else Icons.Default.GridView, contentDescription = "Changer vue")
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.Default.Sort, contentDescription = "Trier")
                                }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Nom") },
                                        onClick = { viewModel.setSortOrder(SortOrder.NAME); showSortMenu = false },
                                        leadingIcon = { if (sortOrder == SortOrder.NAME) Icon(Icons.Default.Check, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Date") },
                                        onClick = { viewModel.setSortOrder(SortOrder.DATE); showSortMenu = false },
                                        leadingIcon = { if (sortOrder == SortOrder.DATE) Icon(Icons.Default.Check, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Taille") },
                                        onClick = { viewModel.setSortOrder(SortOrder.SIZE); showSortMenu = false },
                                        leadingIcon = { if (sortOrder == SortOrder.SIZE) Icon(Icons.Default.Check, null) }
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.scanFiles() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = {},
                        active = false,
                        onActiveChange = {},
                        placeholder = { Text("Rechercher un PDF...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {}

                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Tous") },
                            icon = { Icon(Icons.Default.List, contentDescription = null) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Récents") },
                            icon = { Icon(Icons.Default.History, contentDescription = null) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Favoris") },
                            icon = { Icon(Icons.Default.Favorite, contentDescription = null) }
                        )
                    }
                }
            }
        ) { padding ->
            if (isScanning) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aucun fichier PDF trouvé")
                }
            } else {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredFiles) {
                            pdf ->
                            PdfGridItem(
                                 pdf = pdf,
                                 onClick = {
                                     viewModel.addToRecent(pdf)
                                     selectedPdf = pdf
                                 },
                                 onFavoriteToggle = { viewModel.toggleFavorite(pdf) },
                                 onShare = { sharePdf(context, pdf) },
                                 onRename = { newName -> 
                                      viewModel.renameFile(pdf, newName) { success, message ->
                                          Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                      } 
                                  },
                                  onDelete = { 
                                      viewModel.deleteFile(pdf) { success, message ->
                                          Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                      } 
                                  },
                                  onPrint = { printPdf(context, pdf) },
                                  onInfo = { infoPdf = pdf }
                              )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(filteredFiles) { pdf ->
                            PdfItemRow(
                                 pdf = pdf,
                                 onClick = {
                                     viewModel.addToRecent(pdf)
                                     selectedPdf = pdf
                                 },
                                 onFavoriteToggle = { viewModel.toggleFavorite(pdf) },
                                 onShare = { sharePdf(context, pdf) },
                                 onRename = { newName -> 
                                      viewModel.renameFile(pdf, newName) { success, message ->
                                          Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                      } 
                                  },
                                  onDelete = { 
                                      viewModel.deleteFile(pdf) { success, message ->
                                          Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                      } 
                                  },
                                  onPrint = { printPdf(context, pdf) },
                                  onInfo = { infoPdf = pdf }
                              )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PdfGridItem(
    pdf: PdfFile,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onShare: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onPrint: () -> Unit,
    onInfo: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(pdf.name.substringBeforeLast(".")) }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renommer") },
            text = {
                TextField(value = newName, onValueChange = { newName = it }, label = { Text("Nouveau nom") })
            },
            confirmButton = {
                TextButton(onClick = { onRename(newName); showRenameDialog = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Annuler") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer") },
            text = { Text("Voulez-vous vraiment supprimer ce fichier ?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("Supprimer", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Annuler") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            val (docIcon, docTint) = when (pdf.fileType) {
                FileType.PDF -> Pair(Icons.Default.PictureAsPdf, Color(0xFFE53935))
                FileType.DOCX -> Pair(Icons.Default.Description, Color(0xFF1565C0))
                FileType.XLSX -> Pair(Icons.Default.GridOn, Color(0xFF2E7D32))
            }
            Box(modifier = Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(docIcon, contentDescription = null, modifier = Modifier.size(64.dp), tint = docTint)
                IconButton(onClick = onFavoriteToggle, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(imageVector = if (pdf.isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = "Favori", tint = if (pdf.isFavorite) Color.Red else Color.Gray)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = pdf.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(text = formatFileSize(pdf.size), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Partager") }, onClick = { onShare(); showMenu = false }, leadingIcon = { Icon(Icons.Default.Share, null) })
                        DropdownMenuItem(text = { Text("Imprimer") }, onClick = { onPrint(); showMenu = false }, leadingIcon = { Icon(Icons.Default.Print, null) })
                        DropdownMenuItem(text = { Text("Informations") }, onClick = { onInfo(); showMenu = false }, leadingIcon = { Icon(Icons.Default.Info, null) })
                        DropdownMenuItem(text = { Text("Renommer") }, onClick = { showRenameDialog = true; showMenu = false }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text("Supprimer") }, onClick = { showDeleteDialog = true; showMenu = false }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                    }
                }
            }
        }
    }
}

@Composable
fun PdfItemRow(
    pdf: PdfFile,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onShare: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onPrint: () -> Unit,
    onInfo: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(pdf.name.substringBeforeLast(".")) }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renommer") },
            text = {
                TextField(value = newName, onValueChange = { newName = it }, label = { Text("Nouveau nom") })
            },
            confirmButton = {
                TextButton(onClick = { onRename(newName); showRenameDialog = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Annuler") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer") },
            text = { Text("Voulez-vous vraiment supprimer ce fichier ?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("Supprimer", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Annuler") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val (rowIcon, rowTint) = when (pdf.fileType) {
                FileType.PDF -> Pair(Icons.Default.PictureAsPdf, Color(0xFFE53935))
                FileType.DOCX -> Pair(Icons.Default.Description, Color(0xFF1565C0))
                FileType.XLSX -> Pair(Icons.Default.GridOn, Color(0xFF2E7D32))
            }
            Icon(rowIcon, contentDescription = null, modifier = Modifier.size(40.dp), tint = rowTint)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = pdf.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Row {
                    Text(text = formatFileSize(pdf.size), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(text = " • ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(text = formatDate(pdf.dateModified), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            IconButton(onClick = onFavoriteToggle) {
                Icon(imageVector = if (pdf.isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = "Favori", tint = if (pdf.isFavorite) Color.Red else Color.Gray)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Plus d'options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Partager") }, onClick = { onShare(); showMenu = false }, leadingIcon = { Icon(Icons.Default.Share, null) })
                        DropdownMenuItem(text = { Text("Imprimer") }, onClick = { onPrint(); showMenu = false }, leadingIcon = { Icon(Icons.Default.Print, null) })
                        DropdownMenuItem(text = { Text("Informations") }, onClick = { onInfo(); showMenu = false }, leadingIcon = { Icon(Icons.Default.Info, null) })
                        DropdownMenuItem(text = { Text("Renommer") }, onClick = { showRenameDialog = true; showMenu = false }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text("Supprimer") }, onClick = { showDeleteDialog = true; showMenu = false }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                    }
            }
        }
    }
}

fun openDocument(context: Context, file: PdfFile) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(file.uri, file.fileType.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Ouvrir avec"))
    } catch (e: Exception) {
        Toast.makeText(context, "Aucune application disponible pour ouvrir ce fichier", Toast.LENGTH_SHORT).show()
    }
}

fun sharePdf(context: Context, pdf: PdfFile) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, pdf.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Partager le PDF"))
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatDate(timestamp: Long): String {
    val date = Date(timestamp * 1000)
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    return sdf.format(date)
}