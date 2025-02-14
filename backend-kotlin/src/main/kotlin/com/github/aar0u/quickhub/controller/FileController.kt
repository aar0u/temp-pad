package com.github.aar0u.quickhub.controller

import com.github.aar0u.quickhub.model.ApiResponse
import com.github.aar0u.quickhub.model.Config
import com.github.aar0u.quickhub.model.FileInfo
import com.github.aar0u.quickhub.service.HttpService
import com.github.aar0u.quickhub.service.Loggable
import com.github.aar0u.quickhub.util.FileUtils
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.getMimeTypeForFile
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime

class FileController(private val config: Config) : Loggable, ControllerBase() {
    fun handleFileList(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val jsonObject = parseJsonBody(session)
        val dirname = jsonObject["dirname"] ?: ""
        val fullPath = File(Paths.get(config.workingDir, dirname).normalize().toString())
        log.info("Listing {}", fullPath)

        val fileInfos = mutableListOf<FileInfo>()
        if (fullPath.absolutePath != config.workingDir) {
            fileInfos.add(
                FileInfo(
                    name = "..",
                    path = FileUtils.trimFromBeginning(fullPath.parent ?: config.workingDir, config.workingDir),
                    type = "directory",
                ),
            )
        }

        if (!fullPath.exists()) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                MIME_JSON,
                gson.toJson(
                    ApiResponse(
                        status = "failed",
                        message = "Error listing files",
                        data = mapOf(
                            "folder" to FileUtils.trimFromBeginning(fullPath.absolutePath, config.workingDir),
                            "files" to fileInfos,
                        ),
                    ),
                ),
            )
        }

        fullPath.listFiles()?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })?.forEach { file ->
                fileInfos.add(
                    FileInfo(
                        name = file.name,
                        path = FileUtils.trimFromBeginning(file.absolutePath, config.workingDir),
                        type = if (file.isDirectory) "directory" else "file",
                        size = if (file.isDirectory) null else file.length(),
                        uploadTime = config.dateTimeFormatter?.format(
                            LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(file.lastModified()),
                                java.time.ZoneId.systemDefault(),
                            ),
                        ),
                    ),
                )
            }

        val response = ApiResponse(
            status = "success",
            message = "Files listed successfully",
            data = mapOf(
                "folder" to FileUtils.trimFromBeginning(fullPath.absolutePath, config.workingDir),
                "files" to fileInfos,
            ),
        )

        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            MIME_JSON,
            gson.toJson(response),
        )
    }

    fun handleFileCheck(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val jsonObject = parseJsonBody(session)
        val filename = jsonObject["filename"] ?: ""
        val dirname = jsonObject["dirname"] ?: ""

        if (filename.isBlank()) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                MIME_JSON,
                gson.toJson(
                    ApiResponse(
                        status = "failed",
                        message = "No filename provided",
                    ),
                ),
            )
        }

        val filePath = Paths.get(config.workingDir, dirname, filename).toString()
        if (!config.overwrite && File(filePath).exists()) {
            log.info(
                """
                File already exists:
                $filePath
                """.trimIndent(),
            )
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                MIME_JSON,
                gson.toJson(
                    ApiResponse(
                        status = "failed",
                        message = "File already exists",
                    ),
                ),
            )
        }

        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            MIME_JSON,
            gson.toJson(
                ApiResponse(
                    status = "success",
                    message = "File can be uploaded",
                ),
            ),
        )
    }

    fun handleFileAdd(
        session: NanoHTTPD.IHTTPSession,
        listener: HttpService.CallBackListener? = null,
    ): NanoHTTPD.Response {
        val metadata = session.headers["x-file-metadata"]?.let { metadataStr ->
            runCatching {
                val decode = URLDecoder.decode(metadataStr, StandardCharsets.UTF_8.name())
                gson.fromJson<Map<String, String>>(decode, object : TypeToken<Map<String, String>>() {}.type)
            }.getOrNull()
        } ?: run {
            log.warn("x-file-metadata is null or failed to decode")
            mutableMapOf()
        }

        val uploadDir = Paths.get(config.workingDir, metadata["dirname"] ?: "").toString()
        File(uploadDir).mkdirs() // Ensure upload directory exists

        // Get filename from metadata or fallback to temp file name
        val filename = metadata["filename"]
        val targetFile = File(Paths.get(uploadDir, filename).toString())

        log.info("Upload started: ${targetFile.absolutePath}")

        val map = mutableMapOf<String, String>()
        session.parseBody(map)

        val tempFilePath = map["files"] ?: ""
        log.info("Temp file: {}", tempFilePath)
        val tempFile = File(tempFilePath)

        try {
            targetFile.parentFile?.mkdirs()
            tempFile.copyTo(targetFile, config.overwrite)

            val stats = targetFile.length()
            val fileSizeFormatted = FileUtils.formatFileSize(stats)
            log.info(
                """
                Upload completed:
                - File: ${targetFile.absolutePath}
                - Size: $fileSizeFormatted (${String.format("%,d", stats)} bytes)
                - MIME type: ${getMimeTypeForFile(filename)}
                """.trimIndent(),
            )

            listener?.onFileReceived(targetFile)
        } catch (e: Exception) {
            log.error("Failed to handle file", e)
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                gson.toJson(
                    ApiResponse(
                        status = "failed",
                        message = "Failed to handle file: ${e.message}",
                    ),
                ),
            )
        }

        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            MIME_JSON,
            gson.toJson(
                ApiResponse(
                    status = "success",
                    message = "Files uploaded",
                ),
            ),
        )
    }

    fun handleFileRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val filename = session.uri.removePrefix("/file/")
        val file = File(Paths.get(config.workingDir, filename).toString())
        val rangeHeader = session.headers["range"]
        log.info("Get file: ${file.name} ${rangeHeader?.let { "($it)" } ?: ""}")

        if (!file.exists()) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "File not found",
            )
        }

        val mimeType = Files.probeContentType(file.toPath()) ?: MIME_STREAM
        val response = newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType, null)

        if (rangeHeader == null) {
            response.data = file.inputStream()
            response.addHeader(HEADER_CONTENT_LENGTH, file.length().toString())
        } else {
            val ranges = rangeHeader.substringAfter("bytes=").split("-")
            val start = ranges[0].toLongOrNull() ?: 0
            val end = ranges.getOrNull(1)?.toLongOrNull()?.coerceAtMost(file.length() - 1) ?: (file.length() - 1)

            response.status = NanoHTTPD.Response.Status.PARTIAL_CONTENT
            response.addHeader("Accept-Ranges", "bytes")
            val chunkSize = (end - start) + 1
            if (end == file.length() - 1) {
                val fis = file.inputStream()
                fis.channel.position(start)
                response.data = fis
                response.addHeader(HEADER_CONTENT_LENGTH, chunkSize.toString())
                response.addHeader(HEADER_CONTENT_RANGE, "bytes $start-$end/${file.length()}")
            } else if (end > start) {
                val maxChunkSize = 2 * 1024 * 1024L // Limit buffer to 2MB chunks
                val actualChunk = chunkSize.coerceAtMost(maxChunkSize)
                val buffer = ByteArray(actualChunk.toInt())
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    raf.readFully(buffer)
                }
                response.data = buffer.inputStream()
                response.addHeader(HEADER_CONTENT_LENGTH, actualChunk.toString())
                response.addHeader(HEADER_CONTENT_RANGE, "bytes $start-${start + actualChunk - 1}/${file.length()}")
            }
        }

        return response
    }
}
