package com.example.lecteurpdf

import android.net.Uri

enum class FileType {
    PDF, DOCX, XLSX;

    val mimeType: String
        get() = when (this) {
            PDF -> "application/pdf"
            DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
}

data class PdfFile(
    val name: String,
    val path: String,
    val size: Long,
    val dateModified: Long,
    val uri: Uri,
    val isFavorite: Boolean = false,
    val fileType: FileType = FileType.PDF
)
