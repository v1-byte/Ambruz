package com.ambruz.webanalyzer.model

import java.util.UUID

/** Tingkat akses sah sesuai master plan v2.0 */
enum class AccessLevel {
    /** Public Inspection — tanpa kredensial khusus */
    A_PUBLIC,
    /** Authorized Project Access — izin pemilik */
    B_AUTHORIZED,
    /** Owner-Controlled Rebuild — sistem milik pengguna */
    C_OWNER
}

enum class ProjectStatus {
    DRAFT,
    SCANNING,
    ANALYZED,
    EXPORTED,
    ERROR
}

enum class ResourceKind {
    HTML, CSS, JAVASCRIPT, JSON, XML, IMAGE, ICON, FONT,
    VIDEO, AUDIO, MANIFEST, SERVICE_WORKER, SOURCE_MAP,
    CONFIG, OTHER
}

enum class EndpointClass {
    PUBLIC, AUTH, USER_PROFILE, CONTENT, CONFIG, UPLOAD,
    DOWNLOAD, GAME, PAYMENT, ANALYTICS, WEBSOCKET, UNKNOWN
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val sourceUrl: String,
    val finalUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val accessLevel: AccessLevel = AccessLevel.A_PUBLIC,
    val status: ProjectStatus = ProjectStatus.DRAFT,
    val notes: String = "",
    val resources: List<ResourceRecord> = emptyList(),
    val domains: List<DomainRecord> = emptyList(),
    val endpoints: List<EndpointRecord> = emptyList(),
    val limitations: List<String> = emptyList()
)

data class ResourceRecord(
    val url: String,
    val kind: ResourceKind,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val statusCode: Int? = null,
    val fetched: Boolean = false,
    val localPath: String? = null,
    val sha256: String? = null,
    val error: String? = null,
    val discoveredFrom: String? = null
)

data class DomainRecord(
    val hostname: String,
    val category: String,
    val discoverySource: String,
    val tlsIssuer: String? = null,
    val resolved: Boolean = false
)

data class EndpointRecord(
    val url: String,
    val method: String = "GET",
    val classification: EndpointClass = EndpointClass.UNKNOWN,
    val contentType: String? = null,
    val statusCode: Int? = null,
    val observedAt: Long = System.currentTimeMillis(),
    val triggerPage: String? = null,
    val belongsToUser: Boolean? = null,
    val schemaKnown: Boolean = false
)

data class ScanLimits(
    val maxResources: Int = 200,
    val maxDepth: Int = 3,
    val timeoutMs: Long = 30_000,
    val maxBodyBytes: Long = 5 * 1024 * 1024
)

data class ScanReport(
    val projectId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val resourceCount: Int,
    val domainCount: Int,
    val endpointCount: Int,
    val failures: List<String>,
    val limitations: List<String>
)
