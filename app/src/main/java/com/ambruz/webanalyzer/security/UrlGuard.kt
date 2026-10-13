package com.ambruz.webanalyzer.security

import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * Validasi URL agar tidak memicu SSRF / akses internal yang tidak diizinkan.
 * Pemeriksaan di APK saja tidak cukup jika kelak backend yang fetch — mirror logika di server.
 */
object UrlGuard {

    private val blockedHosts = setOf(
        "localhost", "metadata.google.internal",
        "metadata.google.com", "instance-data"
    )

    fun validateHttpsUrl(raw: String): Result<URL> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("URL kosong"))
        val url = try {
            URL(trimmed)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Format URL tidak valid: ${e.message}"))
        }
        if (url.protocol != "https") {
            return Result.failure(IllegalArgumentException("Hanya HTTPS yang diizinkan"))
        }
        val host = url.host?.lowercase() ?: return Result.failure(IllegalArgumentException("Host kosong"))
        if (host in blockedHosts || host.endsWith(".local") || host.endsWith(".internal")) {
            return Result.failure(IllegalArgumentException("Host diblokir: $host"))
        }
        if (isPrivateOrLocalIp(host)) {
            return Result.failure(IllegalArgumentException("Alamat IP privat/lokal diblokir"))
        }
        return Result.success(url)
    }

    /** Fragmen SPA (#/activity) tidak dikirim ke server HTTP — dicatat terpisah. */
    fun splitFragment(url: URL): Pair<String, String?> {
        val full = url.toString()
        val idx = full.indexOf('#')
        return if (idx >= 0) full.substring(0, idx) to full.substring(idx + 1)
        else full to null
    }

    private fun isPrivateOrLocalIp(host: String): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress
        } catch (_: Exception) {
            // Jika resolusi gagal, biarkan lanjut; error koneksi akan muncul saat fetch
            false
        }
    }

    fun isAllowedRedirect(from: URL, to: URL): Boolean {
        if (to.protocol != "https") return false
        val host = to.host?.lowercase() ?: return false
        if (host in blockedHosts) return false
        return !isPrivateOrLocalIp(host)
    }
}
