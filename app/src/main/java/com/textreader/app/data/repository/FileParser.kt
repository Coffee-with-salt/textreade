package com.textreader.app.data.repository

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * 文件解析器 - 支持 TXT 和 DOCX 格式
 *
 * DOCX 解析说明：DOCX 本质是 ZIP 包，包含 word/document.xml。
 * 我们直接读取这个 XML 并提取 <w:t> 标签内的文本，避免引入
 * 庞大的 Apache POI 库（POI 整个包有 10+MB，APK 会膨胀）。
 */
object FileParser {

    data class ParseResult(
        val text: String,
        val charCount: Int,
        val fileName: String
    )

    /**
     * 从 URI 解析文件
     */
    fun parse(context: Context, uri: Uri): Result<ParseResult> {
        return try {
            val fileName = getFileName(context, uri) ?: "unknown"
            val extension = fileName.substringAfterLast('.', "").lowercase()

            val text = when (extension) {
                "txt" -> parseTxt(context, uri)
                "docx" -> parseDocx(context, uri)
                else -> return Result.failure(
                    Exception("不支持的格式: .$extension\n当前支持: TXT、DOCX")
                )
            }

            if (text.isBlank()) {
                return Result.failure(Exception("文件内容为空"))
            }

            Result.success(
                ParseResult(
                    text = text,
                    charCount = text.length,
                    fileName = fileName
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取文件名
     */
    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    /**
     * 解析 TXT 文件 - 自动检测编码
     */
    private fun parseTxt(context: Context, uri: Uri): String {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法读取文件")

        // 尝试不同编码
        val encodings = listOf("UTF-8", "GBK", "GB2312", "UTF-16", "UTF-16LE", "UTF-16BE")
        for (encoding in encodings) {
            try {
                val text = rawBytes.toString(charset(encoding))
                // 检查是否有效（没有太多乱码）
                if (isValidText(text)) {
                    return text
                }
            } catch (_: Exception) {
                continue
            }
        }

        // 都不行就用 UTF-8
        return rawBytes.toString(Charsets.UTF_8)
    }

    /**
     * 简单检测文本是否有效
     */
    private fun isValidText(text: String): Boolean {
        if (text.length < 10) return true
        val sample = text.take(500)
        val invalidChars = sample.count { it == '\uFFFD' || (it.code in 0x80..0x9F) }
        return invalidChars.toDouble() / sample.length < 0.05
    }

    /**
     * 解析 DOCX 文件（轻量级实现 - 直接读取 word/document.xml）
     *
     * DOCX = ZIP 压缩包，包含 word/document.xml
     * 段落结构: <w:p>...</w:p>
     * 文本节点: <w:t>...</w:t>
     */
    private fun parseDocx(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法读取文件")

        inputStream.use { stream ->
            val zip = ZipInputStream(stream)
            var entry = zip.nextEntry

            // 找到 word/document.xml
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return extractTextFromDocxXml(xml)
                }
                entry = zip.nextEntry
            }

            throw Exception("不是有效的 DOCX 文件（缺少 word/document.xml）")
        }
    }

    /**
     * 从 DOCX 的 XML 中提取纯文本
     * 段落用换行符分隔
     */
    private fun extractTextFromDocxXml(xml: String): String {
        val result = StringBuilder()
        var i = 0
        var inParagraph = false
        var inTextTag = false
        val paragraphText = StringBuilder()

        while (i < xml.length) {
            when {
                // <w:p> 段落开始
                xml.startsWith("<w:p ", i) || xml.startsWith("<w:p>", i) -> {
                    inParagraph = true
                    paragraphText.clear()
                    // 跳过整个标签
                    val end = xml.indexOf('>', i)
                    i = if (end >= 0) end + 1 else i + 1
                    continue
                }
                // </w:p> 段落结束
                xml.startsWith("</w:p>", i) -> {
                    if (inParagraph) {
                        val pText = paragraphText.toString().trim()
                        if (pText.isNotEmpty()) {
                            result.appendLine(pText)
                        }
                        inParagraph = false
                    }
                    i += 6
                    continue
                }
                // <w:t> 或 <w:t ...> 文本开始
                xml.regionMatches(i, "<w:t", 0, 4) -> {
                    inTextTag = true
                    val end = xml.indexOf('>', i)
                    i = if (end >= 0) end + 1 else i + 1
                    continue
                }
                // </w:t> 文本结束
                xml.startsWith("</w:t>", i) -> {
                    inTextTag = false
                    i += 6
                    continue
                }
                // <w:br/> 换行
                xml.startsWith("<w:br", i) -> {
                    if (inParagraph) {
                        paragraphText.append('\n')
                    }
                    val end = xml.indexOf('>', i)
                    i = if (end >= 0) end + 1 else i + 1
                    continue
                }
                // 标签内或 < 字符外的内容
                else -> {
                    val ch = xml[i]
                    if (ch == '<') {
                        // 跳到标签结束
                        val end = xml.indexOf('>', i)
                        i = if (end >= 0) end + 1 else i + 1
                    } else {
                        if (inTextTag) {
                            paragraphText.append(ch)
                        }
                        i++
                    }
                }
            }
        }

        return result.toString().trim()
    }
}
