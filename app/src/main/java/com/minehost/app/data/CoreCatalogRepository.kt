package com.minehost.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

class CoreCatalogRepository {
    companion object {
        /** Canonical host first, legacy launcher host as a fallback. */
        private val MANIFEST_URLS = listOf(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
        )
        private const val FORGE_PROMOS_URL = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
        private const val CATALOG_CONNECT_TIMEOUT_MS = 8_000
        private const val CATALOG_READ_TIMEOUT_MS = 12_000
        private const val USER_AGENT = "MineHost/1.1 (Android server catalog)"

        /**
         * Matches a Minecraft version inside a release tag without matching a
         * longer neighbour: 1.21.1 must not match the 1.21.11 release.
         */
        internal fun versionMentioned(text: String, versionId: String): Boolean = text.contains(
            Regex("(?<![0-9A-Za-z._])" + Regex.escape(versionId) + "(?![0-9.])")
        )

        val platforms: List<CorePlatform> = listOf(
            CorePlatform(
                id = "paper",
                name = "Paper",
                shortName = "P",
                tagline = "Fast, stable, plugin-friendly",
                description = "The balanced all-rounder for vanilla-style servers and plugins.",
                kind = CorePlatformKind.SERVER,
                provider = CoreProvider.PAPER_FILL,
                sourceLabel = "Paper Fill API",
                officialUrl = "https://papermc.io/downloads/paper",
                projectId = "paper"
            ),
            CorePlatform(
                id = "folia",
                name = "Folia",
                shortName = "F",
                tagline = "Region-based performance",
                description = "Paper's fork tuned for modern multi-region server performance.",
                kind = CorePlatformKind.SERVER,
                provider = CoreProvider.PAPER_FILL,
                sourceLabel = "Paper Fill API",
                officialUrl = "https://papermc.io/downloads/folia",
                projectId = "folia"
            ),
            CorePlatform(
                id = "purpur",
                name = "Purpur",
                shortName = "Pu",
                tagline = "Configurable Paper fork",
                description = "A Paper-compatible server with deep configuration and gameplay knobs.",
                kind = CorePlatformKind.SERVER,
                provider = CoreProvider.PURPUR,
                sourceLabel = "Purpur API",
                officialUrl = "https://purpurmc.org/"
            ),
            CorePlatform(
                id = "leaf",
                name = "Leaf",
                shortName = "Le",
                tagline = "Performance-first Paper fork",
                description = "A Paper fork focused on performance, stability, and sensible defaults.",
                kind = CorePlatformKind.SERVER,
                provider = CoreProvider.LEAF_GITHUB,
                sourceLabel = "GitHub Releases",
                officialUrl = "https://www.leafmc.one/en/download",
                githubRepo = "Winds-Studio/Leaf"
            ),
            CorePlatform(
                id = "vanilla",
                name = "Vanilla",
                shortName = "V",
                tagline = "Official Mojang server",
                description = "The unmodified official server jar for a pure Minecraft experience.",
                kind = CorePlatformKind.SERVER,
                provider = CoreProvider.VANILLA,
                sourceLabel = "Mojang manifest",
                officialUrl = "https://www.minecraft.net/download/server"
            ),
            CorePlatform(
                id = "fabric",
                name = "Fabric",
                shortName = "Fa",
                tagline = "Lightweight mod loader",
                description = "Downloads the official Fabric server launcher for the selected version.",
                kind = CorePlatformKind.SERVER,
                provider = CoreProvider.FABRIC_META,
                sourceLabel = "Fabric Meta",
                officialUrl = "https://fabricmc.net/use/server/"
            ),
            CorePlatform(
                id = "forge",
                name = "Forge",
                shortName = "F0",
                tagline = "Classic mod loader",
                description = "Downloads the official installer; a modded server profile must be installed separately.",
                kind = CorePlatformKind.INSTALLER,
                provider = CoreProvider.FORGE_PROMOS,
                sourceLabel = "Forge promotions",
                officialUrl = "https://files.minecraftforge.net/net/minecraftforge/forge/"
            ),
            CorePlatform(
                id = "neoforge",
                name = "NeoForge",
                shortName = "NF",
                tagline = "Modern Forge fork",
                description = "Open the official NeoForge site to install the server profile, then import the generated pack.",
                kind = CorePlatformKind.INSTALLER,
                provider = CoreProvider.MANUAL,
                sourceLabel = "Official project",
                officialUrl = "https://neoforged.net/"
            ),
            CorePlatform(
                id = "pufferfish",
                name = "Pufferfish",
                shortName = "Pf",
                tagline = "Performance-focused fork",
                description = "Open the official Pufferfish download page to choose a build.",
                kind = CorePlatformKind.MANUAL,
                provider = CoreProvider.MANUAL,
                sourceLabel = "Official website",
                officialUrl = "https://pufferfish.host/"
            ),
            CorePlatform(
                id = "spigot",
                name = "Spigot",
                shortName = "Sp",
                tagline = "BuildTools workflow",
                description = "Spigot requires BuildTools; MineHost will not download an unofficial mirror.",
                kind = CorePlatformKind.MANUAL,
                provider = CoreProvider.MANUAL,
                sourceLabel = "BuildTools guide",
                officialUrl = "https://www.spigotmc.org/wiki/buildtools/"
            ),
            CorePlatform(
                id = "magma",
                name = "Magma",
                shortName = "Mg",
                tagline = "Forge + Paper hybrid",
                description = "Open the official project page and choose a trusted release for your server line.",
                kind = CorePlatformKind.MANUAL,
                provider = CoreProvider.MANUAL,
                sourceLabel = "Project website",
                officialUrl = "https://magma.evildead.net/"
            ),
            CorePlatform(
                id = "arclight",
                name = "Arclight",
                shortName = "Ar",
                tagline = "Forge-compatible hybrid",
                description = "Use the official project release page for the exact Minecraft/Forge combination.",
                kind = CorePlatformKind.MANUAL,
                provider = CoreProvider.MANUAL,
                sourceLabel = "Project website",
                officialUrl = "https://github.com/Ibrasertpa/Arclight"
            ),
            CorePlatform(
                id = "optifine",
                name = "OptiFine",
                shortName = "OF",
                tagline = "Client-side graphics mod",
                description = "OptiFine is a Minecraft client mod, not a server core. It cannot host a server.",
                kind = CorePlatformKind.CLIENT_ONLY,
                provider = CoreProvider.MANUAL,
                sourceLabel = "Client project",
                officialUrl = "https://optifine.net/"
            )
        )

        fun platform(id: String): CorePlatform = platforms.firstOrNull { it.id == id } ?: platforms.first()
    }

    private var versionManifest: JSONObject? = null

    suspend fun loadVersions(): List<MinecraftVersion> = withContext(Dispatchers.IO) {
        val manifest = fetchManifest()
        versionManifest = manifest
        val versions = manifest.optJSONArray("versions") ?: JSONArray()
        buildList {
            for (index in 0 until versions.length()) {
                val item = versions.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isNotBlank()) {
                    add(
                        MinecraftVersion(
                            id = id,
                            type = item.optString("type", "release"),
                            releaseTime = item.optString("releaseTime", "")
                        )
                    )
                }
            }
        }
    }

    suspend fun resolve(platform: CorePlatform, version: MinecraftVersion): CoreAsset = withContext(Dispatchers.IO) {
        require(version.id.matches(Regex("[A-Za-z0-9._+-]+"))) { "Invalid Minecraft version" }
        when (platform.provider) {
            CoreProvider.PAPER_FILL -> resolvePaper(platform, version)
            CoreProvider.PURPUR -> resolvePurpur(version)
            CoreProvider.LEAF_GITHUB -> resolveLeaf(platform, version)
            CoreProvider.VANILLA -> resolveVanilla(version)
            CoreProvider.FABRIC_META -> resolveFabric(version)
            CoreProvider.FORGE_PROMOS -> resolveForge(version)
            CoreProvider.MANUAL -> error("This project does not provide a safe direct download in MineHost")
        }
    }

    private fun resolvePaper(platform: CorePlatform, version: MinecraftVersion): CoreAsset {
        val builds = JSONArray(
            fetchText("https://fill.papermc.io/v3/projects/${platform.projectId}/versions/${encode(version.id)}/builds")
        )
        require(builds.length() > 0) { "No ${platform.name} build exists for Minecraft ${version.id}" }
        // Prefer the recommended channel, then stable, then whatever is newest.
        var selected: JSONObject? = null
        var selectedRank = Int.MAX_VALUE
        for (index in 0 until builds.length()) {
            val build = builds.optJSONObject(index) ?: continue
            val rank = when (build.optString("channel").uppercase()) {
                "RECOMMENDED" -> 0
                "STABLE" -> 1
                "BETA" -> 2
                else -> 3
            }
            if (rank < selectedRank) {
                selected = build
                selectedRank = rank
            }
        }
        val build = selected ?: error("No ${platform.name} build exists for Minecraft ${version.id}")
        val downloads = build.optJSONObject("downloads")
            ?: error("${platform.name} did not publish a server download for Minecraft ${version.id}")
        val download = downloads.optJSONObject("server:default")
            ?: error("${platform.name} build ${build.optInt("id")} has no server jar")
        val checksum = download.optJSONObject("checksums")?.optString("sha256").orEmpty()
        val channel = build.optString("channel")
        return CoreAsset(
            platformId = platform.id,
            fileName = download.optString("name", "${platform.name.lowercase()}-${version.id}.jar"),
            downloadUrl = download.getString("url"),
            sizeBytes = download.optLong("size", 0L),
            checksum = checksum.ifBlank { null },
            checksumAlgorithm = "SHA-256",
            sourceLabel = platform.sourceLabel,
            kind = platform.kind,
            note = if (channel.equals("STABLE", ignoreCase = true)) {
                "Stable build resolved for Minecraft ${version.id}."
            } else {
                "Latest available build resolved for Minecraft ${version.id}."
            }
        )
    }

    private fun resolvePurpur(version: MinecraftVersion): CoreAsset {
        val base = "https://api.purpurmc.org/v2/purpur/${encode(version.id)}"
        val versionInfo = fetchJson(base)
        val latestBuild = versionInfo.optJSONObject("builds")?.optString("latest").orEmpty()
        require(latestBuild.isNotBlank()) { "No Purpur build exists for Minecraft ${version.id}" }
        val buildInfo = fetchJson("$base/$latestBuild")
        val md5 = buildInfo.optString("md5").orEmpty()
        return CoreAsset(
            platformId = "purpur",
            fileName = "purpur-${version.id}-$latestBuild.jar",
            downloadUrl = "$base/$latestBuild/download",
            sizeBytes = 0L,
            checksum = md5.ifBlank { null },
            checksumAlgorithm = "MD5",
            sourceLabel = "Purpur API",
            kind = CorePlatformKind.SERVER,
            note = "Latest Purpur build for Minecraft ${version.id}."
        )
    }

    private fun resolveLeaf(platform: CorePlatform, version: MinecraftVersion): CoreAsset {
        val repo = platform.githubRepo ?: error("Leaf GitHub repository is not configured")
        val releases = JSONArray(fetchText("https://api.github.com/repos/$repo/releases?per_page=100"))
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.optJSONObject(releaseIndex) ?: continue
            val searchable = listOf(
                release.optString("tag_name"),
                release.optString("name"),
                release.optString("body")
            ).joinToString(" ")
            if (!versionMentioned(searchable, version.id)) continue
            val assets = release.optJSONArray("assets") ?: JSONArray()
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".jar", ignoreCase = true) || name.contains("source", ignoreCase = true)) continue
                val digest = asset.optString("digest").removePrefix("sha256:").ifBlank { null }
                return CoreAsset(
                    platformId = platform.id,
                    fileName = name,
                    downloadUrl = asset.optString("browser_download_url").ifBlank { null },
                    sizeBytes = asset.optLong("size", 0L),
                    checksum = digest,
                    checksumAlgorithm = "SHA-256",
                    sourceLabel = "GitHub Releases",
                    kind = platform.kind,
                    note = "Release asset matched Minecraft ${version.id}."
                )
            }
        }
        error("No Leaf GitHub release matched Minecraft ${version.id}")
    }

    private fun resolveVanilla(version: MinecraftVersion): CoreAsset {
        val manifest = versionManifest ?: fetchManifest().also { versionManifest = it }
        val versions = manifest.optJSONArray("versions") ?: JSONArray()
        var versionUrl = ""
        for (index in 0 until versions.length()) {
            val item = versions.optJSONObject(index) ?: continue
            if (item.optString("id") == version.id) {
                versionUrl = item.optString("url")
                break
            }
        }
        require(versionUrl.isNotBlank()) { "Mojang did not publish Minecraft ${version.id}" }
        val versionInfo = fetchJson(versionUrl)
        val server = versionInfo.optJSONObject("downloads")?.optJSONObject("server")
            ?: error("No official server jar exists for Minecraft ${version.id}")
        return CoreAsset(
            platformId = "vanilla",
            fileName = "server-${version.id}.jar",
            downloadUrl = server.optString("url").ifBlank { null },
            sizeBytes = server.optLong("size", 0L),
            checksum = server.optString("sha256").ifBlank { null },
            checksumAlgorithm = "SHA-256",
            sourceLabel = "Mojang manifest",
            kind = CorePlatformKind.SERVER,
            note = "Official Mojang server jar for Minecraft ${version.id}."
        )
    }

    private fun resolveFabric(version: MinecraftVersion): CoreAsset {
        val profiles = JSONArray(fetchText("https://meta.fabricmc.net/v2/versions/loader/${encode(version.id)}"))
        require(profiles.length() > 0) { "Fabric has no loader for Minecraft ${version.id}" }
        val profile = profiles.optJSONObject(0) ?: error("Fabric loader data is incomplete")
        val loader = profile.getJSONObject("loader").optString("version")
        val installers = JSONArray(fetchText("https://meta.fabricmc.net/v2/versions/installer"))
        var installer = ""
        for (index in 0 until installers.length()) {
            val item = installers.optJSONObject(index) ?: continue
            if (item.optBoolean("stable", false)) {
                installer = item.optString("version")
                break
            }
        }
        if (installer.isBlank() && installers.length() > 0) {
            installer = installers.optJSONObject(0)?.optString("version").orEmpty()
        }
        require(loader.isNotBlank() && installer.isNotBlank()) { "Fabric loader data is incomplete" }
        val url = "https://meta.fabricmc.net/v2/versions/loader/${encode(version.id)}/${encode(loader)}/${encode(installer)}/server/jar"
        return CoreAsset(
            platformId = "fabric",
            fileName = "fabric-server-launch-$version.jar",
            downloadUrl = url,
            sizeBytes = 0L,
            checksum = null,
            checksumAlgorithm = "SHA-256",
            sourceLabel = "Fabric Meta",
            kind = CorePlatformKind.SERVER,
            note = "Fabric's server launcher downloads its loader dependencies on first run."
        )
    }

    private fun resolveForge(version: MinecraftVersion): CoreAsset {
        val promotions = fetchJson(FORGE_PROMOS_URL)
        val promos = promotions.optJSONObject("promos") ?: JSONObject()
        val recommended = promos.optString("${version.id}-recommended")
        val latest = promos.optString("${version.id}-latest")
        val build = recommended.ifBlank { latest }
        require(build.isNotBlank()) { "Forge has no installer for Minecraft ${version.id}" }
        val artifactVersion = "${version.id}-$build"
        val url = "https://maven.minecraftforge.net/net/minecraftforge/forge/$artifactVersion/forge-$artifactVersion-installer.jar"
        return CoreAsset(
            platformId = "forge",
            fileName = "forge-$artifactVersion-installer.jar",
            downloadUrl = url,
            sizeBytes = 0L,
            checksum = null,
            checksumAlgorithm = "SHA-256",
            sourceLabel = "Forge promotions",
            kind = CorePlatformKind.INSTALLER,
            note = "Installer only. Run it once with Java to create the server profile."
        )
    }

    /** Walks the manifest mirrors until one answers. */
    private fun fetchManifest(): JSONObject {
        var lastError: Exception? = null
        for (url in MANIFEST_URLS) {
            try {
                return fetchJson(url)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IOException("Could not reach the Minecraft version manifest")
    }

    private fun fetchJson(url: String): JSONObject = JSONObject(fetchText(url))

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CATALOG_CONNECT_TIMEOUT_MS
            readTimeout = CATALOG_READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("The release feed returned HTTP $code. Check the selected version or try again.")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: SocketTimeoutException) {
            throw IOException("The release feed timed out. Check your internet connection and try again.", error)
        } catch (error: UnknownHostException) {
            throw IOException("MineHost could not resolve the release server. Check your internet connection.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
