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

class MainActivity : ComponentActivity() {
    private val viewModel: PdfViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LecteurPDFTheme {
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
    var selectedPdf by remember { mutableStateOf<PdfFile?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }

    val filteredFiles = remember(pdfFiles, searchQuery, selectedTab) {
        val baseList = if (selectedTab == 0) pdfFiles else pdfFiles.filter { it.isFavorite }
        if (searchQuery.isEmpty()) baseList
        else baseList.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.scanPdfFiles()
        } else {
            Toast.makeText(context, "Permission refusée", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            viewModel.scanPdfFiles()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    if (selectedPdf != null) {
        PdfViewer(uri = selectedPdf!!.uri, onBack = { selectedPdf = null })
    } else {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("Lecteur PDF Pro") },
                        actions = {
                            IconButton(onClick = { viewModel.scanPdfFiles() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        placeholder = { Text("Rechercher un PDF...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium
                    )
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
                            text = { Text("Favoris") },
                            icon = { Icon(Icons.Default.Favorite, contentDescription = null) }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (filteredFiles.isEmpty()) {
                    Text(
                        if (searchQuery.isEmpty()) {
                            if (selectedTab == 0) "Aucun fichier PDF trouvé" else "Aucun favori pour le moment"
                        } else "Aucun résultat pour \"$searchQuery\"",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredFiles) { pdf ->
                            PdfItemRow(
                                pdf = pdf,
                                onClick = { selectedPdf = pdf },
                                onFavoriteToggle = { viewModel.toggleFavorite(pdf) },
                                onShare = {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, pdf.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Partager le PDF"))
                                }
                            )
                        }
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
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pdf.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${(pdf.size / 1024)} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onFavoriteToggle) {
                Icon(
                    imageVector = if (pdf.isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Favori",
                    tint = if (pdf.isFavorite) Color.Red else Color.Gray
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Partager",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}