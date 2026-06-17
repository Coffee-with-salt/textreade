package com.textreader.app.data.repository

import android.content.Context
import android.net.Uri
import com.textreader.app.data.db.BookDao
import com.textreader.app.data.db.BookEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao
) {

    val allBooks: Flow<List<BookEntity>> = bookDao.getAllBooks()

    /**
     * 导入文件：解析 + 保存文本到内部存储 + 记录到数据库
     */
    suspend fun importFile(uri: Uri): Result<BookEntity> = withContext(Dispatchers.IO) {
        // 解析文件
        val parseResult = FileParser.parse(context, uri).getOrElse {
            return@withContext Result.failure(it)
        }

        // 将文本保存到应用内部私有目录（这样文件移动也不影响阅读）
        val bookDir = File(context.filesDir, "books")
        bookDir.mkdirs()

        val fileName = parseResult.fileName
        val baseName = fileName.substringBeforeLast('.')
        val savedFile = File(bookDir, "$baseName-${System.currentTimeMillis()}.txt")
        savedFile.writeText(parseResult.text, Charsets.UTF_8)

        // 判断原始类型
        val extension = fileName.substringAfterLast('.', "txt").lowercase()

        // 存入数据库
        val book = BookEntity(
            title = baseName,
            filePath = savedFile.absolutePath,
            fileType = extension,
            totalChars = parseResult.charCount,
            lastPosition = 0,
            importTime = System.currentTimeMillis()
        )

        val id = bookDao.insertBook(book)
        Result.success(book.copy(id = id))
    }

    /**
     * 读取书籍文本内容
     */
    suspend fun loadBookText(book: BookEntity): String = withContext(Dispatchers.IO) {
        val file = File(book.filePath)
        if (file.exists()) {
            file.readText(Charsets.UTF_8)
        } else {
            throw Exception("书籍文件不存在，可能已被删除")
        }
    }

    suspend fun getBookById(id: Long): BookEntity? = bookDao.getBookById(id)

    suspend fun updateProgress(bookId: Long, position: Int) {
        bookDao.updateReadingProgress(bookId, position)
    }

    suspend fun deleteBook(book: BookEntity) {
        // 删除内部存储的文本文件
        val file = File(book.filePath)
        if (file.exists()) file.delete()
        // 删除数据库记录
        bookDao.deleteBook(book)
    }
}
