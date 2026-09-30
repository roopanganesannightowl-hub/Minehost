package com.minehost.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

enum class JavaRuntimeStatus {
    NOT_CHECKED,
    WORKING,
    UNAVAILABLE,
    ERROR
}

data class JavaRuntimeCheck(
    val status: JavaRuntimeStatus,
    val message: String,
    val version: String? = null
)

class JavaRuntimeChecker {
    suspend fun check(executable: String): JavaRuntimeCheck = withContext(Dispatchers.IO) {
        val path = executable.trim()
        if (path.isBlank()) {
            return@withContext JavaRuntimeCheck(
                status = JavaRuntimeStatus.UNAVAILABLE,
                message = "No Java executable is configured yet."
            )
        }

        val process = try {
            ProcessBuilder(path, "-version")
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return@withContext JavaRuntimeCheck(
                status = JavaRuntimeStatus.UNAVAILABLE,
                message = "Java could not be started. Check the executable path or install an accessible Java runtime."
            )
        }

        try {
            val finished = process.waitFor(6, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext JavaRuntimeCheck(
                    status = JavaRuntimeStatus.ERROR,
                    message = "Java did not respond within 6 seconds. The executable may not be an Android-compatible runtime."
                )
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() != 0) {
                return@withContext JavaRuntimeCheck(
                    status = JavaRuntimeStatus.ERROR,
                    message = output.lineSequence().firstOrNull().orEmpty()
                        .ifBlank { "The Java executable exited with an error." }
                )
            }

            val version = output.lineSequence().firstOrNull { it.isNotBlank() }
                ?.take(180)
                .orEmpty()
            JavaRuntimeCheck(
                status = JavaRuntimeStatus.WORKING,
                message = "Java runtime is ready.",
                version = version.ifBlank { "Java responded successfully." }
            )
        } catch (_: Exception) {
            JavaRuntimeCheck(
                status = JavaRuntimeStatus.ERROR,
                message = "The Java runtime could not be verified."
            )
        } finally {
            process.destroy()
        }
    }
}
