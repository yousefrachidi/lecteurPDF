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
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor

import java.io.File

enum class SortOrder {
    NAME, DATE, SIZE
}

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE)
    
    private val _pdfFiles = MutableStateFlow<List<PdfFile>>(emptyList())
    val pdfFiles: StateFlow<List<PdfFile>> = _pdfFiles

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _isGridView = MutableStateFlow(prefs.getBoolean("is_grid_view", false))
    val isGridView: StateFlow<Boolean> = _isGridView

    private val _sortOrder = MutableStateFlow(SortOrder.valueOf(prefs.getString("sort_order", SortOrder.DATE.name) ?: SortOrder.DATE.name))
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val _isAppDarkMode = MutableStateFlow(prefs.getBoolean("is_app_dark_mode", false))
    val isAppDarkMode: StateFlow<Boolean> = _isAppDarkMode

    fun toggleAppDarkMode() {
        val newValue = !_isAppDarkMode.value
        _isAppDarkMode.value = newValue
        prefs.edit().putBoolean("is_app_dark_mode", newValue).apply()
    }

    fun toggleGridView() {
        val newValue = !_isGridView.value
        _isGridView.value = newValue
        prefs.edit().putBoolean("is_grid_view", newValue).apply()
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        prefs.edit().putString("sort_order", order.name).apply()
        sortCurrentFiles()
    }

    private fun sortCurrentFiles() {
        val currentList = _pdfFiles.value
        _pdfFiles.value = when (_sortOrder.value) {
            SortOrder.NAME -> currentList.sortedBy { it.name.lowercase() }
            SortOrder.DATE -> currentList.sortedByDescending { it.dateModified }
            SortOrder.SIZE -> currentList.sortedByDescending { it.size }
        }
    }

    fun addToRecent(pdfFile: PdfFile) {
        val recents = prefs.getStringSet("recents", emptySet())?.toMutableSet() ?: mutableSetOf()
        recents.remove(pdfFile.path)
        val newList = mutableListOf(pdfFile.path)
        newList.addAll(recents.take(19)) // Keep last 20
        prefs.edit().putStringSet("recents", newList.toSet()).apply()
    }

    fun getRecentPaths(): Set<String> {
        return prefs.getStringSet("recents", emptySet()) ?: emptySet()
    }

    fun getPageCount(pdfFile: PdfFile): Int {
        return try {
            val fileDescriptor = getApplication<Application>().contentResolver.openFileDescriptor(pdfFile.uri, "r")
            if (fileDescriptor != null) {
                val renderer = PdfRenderer(fileDescriptor)
                val count = renderer.pageCount
                renderer.close()
                fileDescriptor.close()
                count
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    fun deleteFile(pdfFile: PdfFile, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val deleted = getApplication<Application>().contentResolver.delete(pdfFile.uri, null, null)
                withContext(Dispatchers.Main) {
                    if (deleted > 0) {
                        onResult(true, "Fichier supprimé")
                        scanFiles()
                    } else {
                        onResult(false, "Impossible de supprimer le fichier")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Erreur: ${e.message}")
                }
            }
        }
    }

    fun renameFile(pdfFile: PdfFile, newName: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, "$newName.pdf")
                }
                val updated = getApplication<Application>().contentResolver.update(pdfFile.uri, values, null, null)
                withContext(Dispatchers.Main) {
                    if (updated > 0) {
                        onResult(true, "Fichier renommé")
                        scanFiles()
                    } else {
                        onResult(false, "Impossible de renommer le fichier")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Erreur: ${e.message}")
                }
            }
        }
    }

    fun toggleFavorite(pdfFile: PdfFile) {
        val favorites = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (favorites.contains(pdfFile.path)) {
            favorites.remove(pdfFile.path)
        } else {
            favorites.add(pdfFile.path)
        }
        prefs.edit().putStringSet("favorites", favorites).apply()
        
        // Update the list to reflect change
        _pdfFiles.value = _pdfFiles.value.map {
            if (it.path == pdfFile.path) it.copy(isFavorite = favorites.contains(it.path)) else it
        }
    }

    fun scanFiles() {
        viewModelScope.launch {
            _isScanning.value = true
            val files = withContext(Dispatchers.IO) {
                val list = mutableListOf<PdfFile>()
                val favorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
                
                val projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED
                )
                
                val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
                val selectionArgs = arrayOf("application/pdf")
                
                getApplication<Application>().contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        val path = cursor.getString(pathCol)
                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                        
                        list.add(PdfFile(name, path, size, date, uri, favorites.contains(path)))
                    }
                }
                list
            }
            _pdfFiles.value = when (_sortOrder.value) {
                SortOrder.NAME -> files.sortedBy { it.name.lowercase() }
                SortOrder.DATE -> files.sortedByDescending { it.dateModified }
                SortOrder.SIZE -> files.sortedByDescending { it.size }
            }
            _isScanning.value = false
        }
    }
}
