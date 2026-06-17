package com.textreader.app.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.textreader.app.data.db.BookEntity
import com.textreader.app.viewmodel.BookshelfViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onOpenBook: (Long) -> Unit,
    viewModel: BookshelfViewModel = hiltViewModel()
) {
    val books by viewModel.books.collectAsState(initial = emptyList())
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf<BookEntity?>(null) }

    // 文件选择器（TXT + DOCX）
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFile(it) }
    }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            filePicker.launch(arrayOf(
                "text/plain",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword",
                "*/*"
            ))
        }
    }

    // 导入成功提示
    LaunchedEffect(uiState.lastImportedBook) {
        if (uiState.lastImportedBook != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearLastImported()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "书架",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            filePicker.launch(arrayOf(
                                "text/plain",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/msword",
                                "*/*"
                            ))
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "导入文件")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        filePicker.launch(arrayOf(
                            "text/plain",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/msword",
                            "*/*"
                        ))
                    } else {
                        permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                },
                icon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                text = { Text("导入文件") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (books.isEmpty()) {
                EmptyBookshelf(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 80.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            onDelete = { showDeleteDialog = book }
                        )
                    }
                }
            }

            // 导入中加载指示器
            if (uiState.isImporting) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("正在导入文件...")
                        }
                    }
                }
            }
        }
    }

    // 错误提示
    uiState.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("导入失败") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("确定") }
            }
        )
    }

    // 删除确认
    showDeleteDialog?.let { book ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除确认") },
            text = { Text("确定要删除《${book.title}》吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(book)
                        showDeleteDialog = null
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val progress = if (book.totalChars > 0) {
        book.lastPosition.toFloat() / book.totalChars
    } else 0f

    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINESE)
    val dateStr = if (book.lastReadTime > 0) {
        formatter.format(Date(book.lastReadTime))
    } else {
        formatter.format(Date(book.importTime))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDelete
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 书籍图标
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (book.fileType == "docx" || book.fileType == "doc")
                            Icons.Outlined.Description else Icons.Outlined.Article,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 格式标签
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    book.fileType.uppercase(),
                                    fontSize = 10.sp
                                )
                            },
                            modifier = Modifier.height(22.dp)
                        )
                        Text(
                            text = "${book.totalChars / 1000}k字",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 阅读进度条
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (progress > 0.01f) "已读 ${(progress * 100).toInt()}%" else "未开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "长按删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
fun EmptyBookshelf(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Outlined.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Text(
            "书架空空如也",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        Text(
            "点击右下角按钮导入 TXT 或 DOCX 文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        )
    }
}
