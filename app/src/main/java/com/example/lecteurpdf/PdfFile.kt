package com.example.lecteurpdf

import android.net.Uri

data class PdfFile(
    val name: String,
    val path: String,
    val size: Long,
    val dateModified: Long,
    val uri: Uri,
    val isFavorite: Boolean = false
)
