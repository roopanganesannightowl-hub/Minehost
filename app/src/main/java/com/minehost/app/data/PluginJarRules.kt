package com.minehost.app.data

/**
 * Pure rules for plugin JARs picked from device storage.
 *
 * A plugin is only useful to Paper/Spigot if it is a real JAR carrying a
 * descriptor (`plugin.yml`, or `paper-plugin.yml` for the modern format), so
 * those checks happen before anything is copied into the workspace. Kept free
 * of Android types so the rules are unit-testable on the JVM.
 */
object PluginJarRules {

    /** Larger than any real plugin; guards against a mistaken video/ROM pick. */
    const val MAX_PLUGIN_BYTES = 128L * 1024L * 1024L

    /** A sane upper bound on classes inside a plugin JAR. */
    const val MAX_ENTRIES = 20_000

    /** Descriptor files Paper/Spigot read from the root of a plugin JAR. */
    val DESCRIPTOR_ENTRIES = listOf("plugin.yml", "paper-plugin.yml")

    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    private const val MAX_NAME_LENGTH = 80

    /** The interesting header fields of a `plugin.yml`. */
    data class Descriptor(
        val name: String? = null,
        val version: String? = null,
        val main: String? = null,
        val apiVersion: String? = null
    ) {
        /** "EssentialsX 2.20.1" — what the plugin list shows next to the file. */
        fun label(): String = when {
            name != null && version != null -> "$name $version"
            name != null -> name
            else -> ""
        }
    }

    /**
     * Turns a picked display name into a safe, collision-free file name that
     * always ends in `.jar`. Path separators and traversal are stripped first —
     * the result is used as a plain file name, never as a path.
     */
    fun safeFileName(displayName: String): String {
        val base = displayName
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
        val withoutExtension = base.substringBeforeLast('.', base)
            .ifBlank { "plugin" }
        val cleaned = withoutExtension
            .replace(UNSAFE_NAME_CHARS, "_")
            .replace(Regex("_{2,}"), "_")
            .trim('_', '.')
            .take(MAX_NAME_LENGTH)
            .ifBlank { "plugin" }
        return if (cleaned.startsWith('.')) "_$cleaned.jar" else "$cleaned.jar"
    }

    /** True when the leading bytes are the `PK` ZIP/JAR signature. */
    fun isZipArchive(header: ByteArray): Boolean =
        header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()

    /**
     * Returns why a candidate JAR cannot be installed, or null when it is fine.
     * The message is user-facing: it is shown verbatim as a snackbar.
     */
    fun rejectionReason(
        fileName: String,
        sizeBytes: Long,
        isZip: Boolean,
        entryNames: List<String>
    ): String? = when {
        sizeBytes <= 0L -> "That file is empty."
        sizeBytes > MAX_PLUGIN_BYTES -> "That JAR is larger than 128 MB."
        !fileName.endsWith(".jar", ignoreCase = true) -> "Plugins must be .jar files."
        !isZip -> "That file is not a valid JAR archive."
        entryNames.size > MAX_ENTRIES -> "That JAR contains too many files."
        entryNames.none { it in DESCRIPTOR_ENTRIES } ->
            "That JAR has no plugin.yml, so Paper would not load it."
        else -> null
    }

    /**
     * Minimal read of the top-level keys in a plugin descriptor. Only root
     * scalars are read (no YAML library, and nesting is irrelevant here).
     */
    fun readDescriptor(text: String): Descriptor {
        val top = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#") || raw.trimStart().startsWith("-")) continue
            if (raw.first().isWhitespace()) continue // nested value, not a root key
            val separator = raw.indexOf(':')
            if (separator <= 0) continue
            val key = raw.substring(0, separator).trim()
            if (key.isEmpty() || key in top) continue
            top[key] = raw.substring(separator + 1).trim().trim('"', '\'')
        }
        return Descriptor(
            name = top["name"]?.takeIf { it.isNotBlank() },
            version = top["version"]?.takeIf { it.isNotBlank() },
            main = top["main"]?.takeIf { it.isNotBlank() },
            apiVersion = (top["api-version"] ?: top["apiVersion"])?.takeIf { it.isNotBlank() }
        )
    }
}