package com.minehost.app.data

enum class CorePlatformKind {
    SERVER,
    INSTALLER,
    CLIENT_ONLY,
    MANUAL
}

enum class CoreProvider {
    VANILLA,
    PAPER_FILL,
    PURPUR,
    LEAF_GITHUB,
    FABRIC_META,
    FORGE_PROMOS,
    MANUAL
}

data class CorePlatform(
    val id: String,
    val name: String,
    val shortName: String,
    val tagline: String,
    val description: String,
    val kind: CorePlatformKind,
    val provider: CoreProvider,
    val sourceLabel: String,
    val officialUrl: String,
    val projectId: String = "",
    val githubRepo: String? = null
)

data class MinecraftVersion(
    val id: String,
    val type: String,
    val releaseTime: String
)

data class CoreAsset(
    val platformId: String,
    val fileName: String,
    val downloadUrl: String?,
    val sizeBytes: Long,
    val checksum: String?,
    val checksumAlgorithm: String,
    val sourceLabel: String,
    val kind: CorePlatformKind,
    val note: String
) {
    val canInstallAsCore: Boolean
        get() = kind == CorePlatformKind.SERVER && downloadUrl != null
}

data class CatalogUiState(
    val isLoadingVersions: Boolean = false,
    val versions: List<MinecraftVersion> = emptyList(),
    val selectedVersion: MinecraftVersion? = null,
    val selectedPlatformId: String = "paper",
    val asset: CoreAsset? = null,
    val isResolving: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedName: String? = null,
    val error: String? = null,
    val notice: String? = null
)
