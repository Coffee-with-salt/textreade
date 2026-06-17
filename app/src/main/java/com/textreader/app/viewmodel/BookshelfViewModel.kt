package com.textreader.app.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textreader.app.data.db.BookEntity
import com.textreader.app.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val repository: BookRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val books = repository.allBooks

    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    /**
     * 导入文件
     */
    fun importFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, errorMessage = null)
            repository.importFile(uri).fold(
                onSuccess = { book ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        lastImportedBook = book
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        errorMessage = error.message ?: "导入失败"
                    )
                }
            )
        }
    }

    /**
     * 删除书籍
     */
    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            repository.deleteBook(book)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun clearLastImported() {
        _uiState.value = _uiState.value.copy(lastImportedBook = null)
    }
}

data class BookshelfUiState(
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
    val lastImportedBook: BookEntity? = null
)
