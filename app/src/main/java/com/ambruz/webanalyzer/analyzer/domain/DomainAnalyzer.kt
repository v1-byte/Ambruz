package com.ambruz.webanalyzer.analyzer.domain

import com.ambruz.webanalyzer.model.DomainRecord
import com.ambruz.webanalyzer.model.ResourceRecord
import java.net.URI

/** M4: petakan hostname dari bukti penemuan, bukan asumsi kepemilikan. */
class DomainAnalyzer {
    fun analyze(startUrl: String, resources: List<ResourceRecord>): List<DomainRecord> {
        val map = linkedMapOf<String, DomainRecord>()
        fun put(host: String, category: String, source: String) {
            val h = host.lowercase()
            if (h.isBlank()) return
            map.putIfAbsent(h, DomainRecord(h, category, source))
        }
        hostOf(startUrl)?.let { put(it, "Website", "URL awal") }
        resources.forEach { r ->
            hostOf(r.url)?.let { put(it, categoryFor(r), "resource:${r.kind}") }
        }
        return map.values.toList()
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }

    private fun categoryFor(r: ResourceRecord): String = when {
        r.url.contains("api", ignoreCase = true) -> "API candidate"
        r.kind.name.contains("IMAGE") || r.kind.name.contains("FONT") -> "Asset host"
        else -> "Related host"
    }
}
