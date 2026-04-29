package com.example.lecteurpdf

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

data class DocxRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

data class DocxParagraph(
    val runs: List<DocxRun>,
    val headingLevel: Int = 0,
    val isCentered: Boolean = false
) {
    val text: String get() = runs.joinToString("") { it.text }
    val isEmpty: Boolean get() = text.isBlank()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocxViewer(uri: Uri, fileName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var paragraphs by remember { mutableStateOf<List<DocxParagraph>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                paragraphs = parseDocx(context, uri)
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
                        Text("Document Word", style = MaterialTheme.typography.bodySmall)
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
                paragraphs.isEmpty() -> Text(
                    "Document vide",
                    Modifier.align(Alignment.Center)
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    items(paragraphs) { para ->
                        if (para.isEmpty) {
                            Spacer(Modifier.height(6.dp))
                        } else {
                            val fontSize = when (para.headingLevel) {
                                1 -> 24.sp
                                2 -> 20.sp
                                3 -> 18.sp
                                4 -> 16.sp
                                else -> 14.sp
                            }
                            val annotated = buildAnnotatedString {
                                para.runs.forEach { run ->
                                    withStyle(
                                        SpanStyle(
                                            fontWeight = if (run.bold || para.headingLevel > 0) FontWeight.Bold else FontWeight.Normal,
                                            fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
                                            textDecoration = if (run.underline) TextDecoration.Underline else TextDecoration.None
                                        )
                                    ) {
                                        append(run.text)
                                    }
                                }
                            }
                            Text(
                                text = annotated,
                                fontSize = fontSize,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (para.headingLevel > 0) 8.dp else 4.dp),
                                textAlign = if (para.isCentered) TextAlign.Center else TextAlign.Start
                            )
                            if (para.headingLevel == 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun parseDocx(context: Context, uri: Uri): List<DocxParagraph> {
    var documentBytes: ByteArray? = null
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentBytes = zip.readBytes()
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
    return documentBytes?.let { parseDocumentXml(it.inputStream()) } ?: emptyList()
}

private fun XmlPullParser.attrValue(localName: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i) == localName) return getAttributeValue(i)
    }
    return null
}

private fun parseHeadingLevel(style: String): Int {
    val lower = style.lowercase()
    return when {
        lower.startsWith("heading") || lower.startsWith("titre") -> {
            style.lastOrNull()?.digitToIntOrNull() ?: 1
        }
        lower == "title" -> 1
        lower == "subtitle" -> 2
        else -> 0
    }
}

private fun parseDocumentXml(inputStream: InputStream): List<DocxParagraph> {
    val paragraphs = mutableListOf<DocxParagraph>()
    val factory = XmlPullParserFactory.newInstance().also { it.isNamespaceAware = true }
    val parser = factory.newPullParser()
    parser.setInput(inputStream, "UTF-8")

    var inBody = false
    var inParagraph = false
    var inRun = false
    var inRpr = false
    var inPpr = false
    var inText = false
    var currentRuns = mutableListOf<DocxRun>()
    var currentBold = false
    var currentItalic = false
    var currentUnderline = false
    var currentText = StringBuilder()
    var headingLevel = 0
    var isCentered = false

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name ?: ""
        when (event) {
            XmlPullParser.START_TAG -> when {
                tag == "body" -> inBody = true
                !inBody -> Unit
                tag == "p" && !inParagraph -> {
                    inParagraph = true
                    currentRuns = mutableListOf()
                    headingLevel = 0
                    isCentered = false
                }
                inParagraph && tag == "pPr" -> inPpr = true
                inPpr && tag == "pStyle" -> headingLevel = parseHeadingLevel(parser.attrValue("val") ?: "")
                inPpr && tag == "jc" -> isCentered = (parser.attrValue("val") ?: "") == "center"
                inParagraph && !inPpr && tag == "r" -> {
                    inRun = true
                    currentBold = false
                    currentItalic = false
                    currentUnderline = false
                    currentText = StringBuilder()
                }
                inRun && tag == "rPr" -> inRpr = true
                inRpr && tag == "b" -> {
                    val v = parser.attrValue("val")
                    currentBold = v == null || (v != "false" && v != "0")
                }
                inRpr && tag == "i" -> {
                    val v = parser.attrValue("val")
                    currentItalic = v == null || (v != "false" && v != "0")
                }
                inRpr && tag == "u" -> currentUnderline = (parser.attrValue("val") ?: "none") != "none"
                inRun && !inRpr && tag == "t" -> inText = true
                inRun && !inRpr && tag == "br" -> currentText.append("\n")
            }
            XmlPullParser.TEXT -> if (inText) currentText.append(parser.text)
            XmlPullParser.END_TAG -> when {
                tag == "body" -> inBody = false
                tag == "pPr" -> inPpr = false
                tag == "rPr" -> inRpr = false
                tag == "t" -> inText = false
                tag == "r" && inRun -> {
                    if (currentText.isNotEmpty()) {
                        currentRuns.add(DocxRun(currentText.toString(), currentBold, currentItalic, currentUnderline))
                    }
                    inRun = false
                }
                tag == "p" && inParagraph && inBody -> {
                    paragraphs.add(DocxParagraph(currentRuns.toList(), headingLevel, isCentered))
                    inParagraph = false
                }
            }
        }
        event = parser.next()
    }
    return paragraphs
}
