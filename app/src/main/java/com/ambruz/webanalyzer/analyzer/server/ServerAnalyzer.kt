package com.ambruz.webanalyzer.analyzer.server

/**
 * M4: metadata server yang diamati dari respons publik.
 * Tidak mengklaim teknologi backend pasti hanya dari header.
 */
data class ServerObservation(
    val url: String,
    val statusCode: Int?,
    val serverHeader: String?,
    val poweredBy: String?,
    val contentType: String?,
    val cacheControl: String?,
    val notes: List<String>
)

class ServerAnalyzer {
    fun fromHeaders(url: String, status: Int?, headers: Map<String, String>): ServerObservation {
        val notes = mutableListOf<String>()
        val server = headers.entries.firstOrNull { it.key.equals("server", true) }?.value
        val powered = headers.entries.firstOrNull { it.key.equals("x-powered-by", true) }?.value
        if (server != null) notes += "Header Server terlihat; bukan bukti pasti stack backend."
        if (powered != null) notes += "X-Powered-By terlihat; dapat dimatikan/diubah oleh operator."
        notes += "Informasi privat (log, env, DB) memerlukan akses Level B/C dari pemilik."
        return ServerObservation(
            url = url,
            statusCode = status,
            serverHeader = server,
            poweredBy = powered,
            contentType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value,
            cacheControl = headers.entries.firstOrNull { it.key.equals("cache-control", true) }?.value,
            notes = notes
        )
    }
}
