package com.example.lecteurpdf

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class XlsxData(
    val sheetNames: List<String>,
    val sheets: List<List<List<String>>>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XlsxViewer(uri: Uri, fileName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var xlsxData by remember { mutableStateOf<XlsxData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSheet by remember { mutableIntStateOf(0) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                xlsxData = parseXlsx(context, uri)
            } catch (e: Exception) {
                error = "Impossible de lire le fichier: ${e.message}"
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Feuille de calcul", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(16.dp))
                xlsxData != null -> {
                    val data = xlsxData!!
                    Column(Modifier.fillMaxSize()) {
                        if (data.sheetNames.size > 1) {
                            val safeTab = selectedSheet.coerceIn(0, data.sheetNames.lastIndex)
                            ScrollableTabRow(selectedTabIndex = safeTab) {
                                data.sheetNames.forEachIndexed { i, name ->
                                    Tab(
                                        selected = safeTab == i,
                                        onClick = { selectedSheet = i },
                                        text = { Text(name) }
                                    )
                                }
                            }
                        }
                        val rows = data.sheets.getOrElse(selectedSheet) { emptyList() }
                        if (rows.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Feuille vide")
                            }
                        } else {
                            val colCount = rows.maxOf { it.size }.coerceAtLeast(1)
                            val hScroll = rememberScrollState()
                            LazyColumn(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                                itemsIndexed(rows) { rowIndex, row ->
                                    val isHeader = rowIndex == 0
                                    val bg = when {
                                        isHeader -> MaterialTheme.colorScheme.primaryContainer
                                        rowIndex % 2 == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                    Row(
                                        Modifier
                                            .background(bg)
                                            .fillMaxWidth()
                                    ) {
                                        repeat(colCount) { colIndex ->
                                            val cell = row.getOrElse(colIndex) { "" }
                                            Text(
                                                text = cell,
                                                modifier = Modifier
                                                    .width(150.dp)
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2
                                            )
                                            if (colIndex < colCount - 1) {
                                                Box(
                                                    Modifier
                                                        .width(1.dp)
                                                        .height(32.dp)
                                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun parseXlsx(context: Context, uri: Uri): XlsxData {
    val sharedStrings = mutableListOf<String>()
    val sheetBytesMap = mutableMapOf<String, ByteArray>()
    var workbookBytes: ByteArray? = null

    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "xl/sharedStrings.xml" -> parseSharedStrings(zip, sharedStrings)
                    entry.name == "xl/workbook.xml" -> workbookBytes = zip.readBytes()
                    entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) -> sheetBytesMap[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    val sheetNames = workbookBytes?.let { parseWorkbookNames(ByteArrayInputStream(it)) } ?: emptyList()
    val sheets = sheetBytesMap.entries
        .sortedBy { Regex("\\d+").find(it.key)?.value?.toIntOrNull() ?: 0 }
        .map { parseSheetData(ByteArrayInputStream(it.value), sharedStrings) }

    return XlsxData(
        sheetNames = sheetNames.ifEmpty { sheets.indices.map { "Feuille ${it + 1}" } },
        sheets = sheets
    )
}

private fun parseSharedStrings(inputStream: InputStream, result: MutableList<String>) {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(inputStream, "UTF-8")
    var inSi = false
    var inT = false
    var current = StringBuilder()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "si" -> { inSi = true; current = StringBuilder() }
                "t" -> inT = true
            }
            XmlPullParser.TEXT -> if (inT) current.append(parser.text)
            XmlPullParser.END_TAG -> when (parser.name) {
                "t" -> inT = false
                "si" -> { result.add(current.toString()); inSi = false }
            }
        }
        event = parser.next()
    }
}

private fun parseWorkbookNames(inputStream: InputStream): List<String> {
    val names = mutableListOf<String>()
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(inputStream, "UTF-8")
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
            val name = (0 until parser.attributeCount)
                .firstOrNull { parser.getAttributeName(it) == "name" }
                ?.let { parser.getAttributeValue(it) } ?: "Feuille"
            names.add(name)
        }
        event = parser.next()
    }
    return names
}

private fun parseSheetData(inputStream: InputStream, sharedStrings: List<String>): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(inputStream, "UTF-8")

    var currentRow: MutableList<String>? = null
    var cellType = ""
    var cellRef = ""
    var inV = false
    var inT = false
    var cellVal = StringBuilder()

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> currentRow = mutableListOf()
                "c" -> {
                    cellType = (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it) == "t" }
                        ?.let { parser.getAttributeValue(it) } ?: "n"
                    cellRef = (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it) == "r" }
                        ?.let { parser.getAttributeValue(it) } ?: ""
                    cellVal = StringBuilder()
                }
                "v" -> inV = true
                "t" -> inT = true
            }
            XmlPullParser.TEXT -> if (inV || inT) cellVal.append(parser.text)
            XmlPullParser.END_TAG -> when (parser.name) {
                "v" -> inV = false
                "t" -> inT = false
                "c" -> {
                    val raw = cellVal.toString()
                    val display = when (cellType) {
                        "s" -> sharedStrings.getOrElse(raw.toIntOrNull() ?: -1) { raw }
                        "b" -> if (raw == "1") "VRAI" else "FAUX"
                        "e" -> "#ERR"
                        else -> raw
                    }
                    val colIdx = cellRefToColIndex(cellRef)
                    if (colIdx >= 0) {
                        val row = currentRow ?: mutableListOf()
                        while (row.size <= colIdx) row.add("")
                        row[colIdx] = display
                    }
                }
                "row" -> currentRow?.let {
                    if (it.any { c -> c.isNotEmpty() }) rows.add(it)
                }
            }
        }
        event = parser.next()
    }
    return rows
}

private fun cellRefToColIndex(ref: String): Int {
    val col = ref.takeWhile { it.isLetter() }.uppercase()
    if (col.isEmpty()) return -1
    return col.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
}
