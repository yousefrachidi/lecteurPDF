package com.example.lecteurpdf

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.content.Context
import android.content.SharedPreferences

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE)
    
    private val _pdfFiles = MutableStateFlow<List<PdfFile>>(emptyList())
    val pdfFiles: StateFlow<List<PdfFile>> = _pdfFiles

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    fun toggleFavorite(pdfFile: PdfFile) {
        val currentFavorites = prefs.getStringSet("favorites", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (pdfFile.isFavorite) {
            currentFavorites.remove(pdfFile.path)
        } else {
            currentFavorites.add(pdfFile.path)
        }
        prefs.edit().putStringSet("favorites", currentFavorites).apply()
        
        // Update the list in memory
        _pdfFiles.value = _pdfFiles.value.map {
            if (it.path == pdfFile.path) it.copy(isFavorite = !it.isFavorite) else it
        }
    }

    private fun isPathFavorite(path: String): Boolean {
        val favorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        return favorites.contains(path)
    }

    fun scanPdfFiles() {
        viewModelScope.launch {
            _isScanning.value = true
            val files = queryPdfFiles()
            _pdfFiles.value = files
            _isScanning.value = false
        }
    }

    private suspend fun queryPdfFiles(): List<PdfFile> = withContext(Dispatchers.IO) {
        val pdfList = mutableListOf<PdfFile>()
        val collection = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns._ID
        )

        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val selectionArgs = arrayOf("application/pdf")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        getApplication<Application>().contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                val path = cursor.getString(pathColumn)
                val size = cursor.getLong(sizeColumn)
                val date = cursor.getLong(dateColumn)
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val isFavorite = isPathFavorite(path)

                pdfList.add(PdfFile(name, path, size, date, uri, isFavorite))
            }
        }
        pdfList
    }
}
