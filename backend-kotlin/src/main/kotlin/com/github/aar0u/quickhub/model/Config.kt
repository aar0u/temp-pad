package com.github.aar0u.quickhub.model

import java.time.format.DateTimeFormatter

data class Config(
    val workingDir: String,
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 3006,
    val host: String = System.getenv("HOST") ?: "0.0.0.0",
    val dateTimeFormatter: DateTimeFormatter? = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    val staticDir: String = "/static",
    val overwrite: Boolean = false,
)
