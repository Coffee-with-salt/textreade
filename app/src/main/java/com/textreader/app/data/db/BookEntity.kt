package com.textreader.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书籍实体 - 存储导入的文件信息
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,           // 书名（文件名去掉扩展名）
    val filePath: String,        // 文件原始路径（或复制到应用内部的路径）
    val fileType: String,        // "txt" 或 "docx"
    val totalChars: Int = 0,     // 总字符数
    val lastPosition: Int = 0,   // 上次阅读位置（字符索引）
    val importTime: Long = System.currentTimeMillis(),
    val lastReadTime: Long = 0
)
