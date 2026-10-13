package com.ambruz.webanalyzer.analyzer.game

import com.ambruz.webanalyzer.model.ResourceKind
import com.ambruz.webanalyzer.model.ResourceRecord

/**
 * M12: inventaris resource game yang diizinkan.
 * Tidak merekonstruksi logika server / RNG / hasil pertandingan.
 */
data class GameInventory(
    val pageUrl: String,
    val assets: List<ResourceRecord>,
    val notes: List<String>
)

class GameResourceAnalyzer {
    fun inventory(pageUrl: String, resources: List<ResourceRecord>): GameInventory {
        val assets = resources.filter {
            it.kind in setOf(
                ResourceKind.IMAGE, ResourceKind.AUDIO, ResourceKind.VIDEO,
                ResourceKind.JAVASCRIPT, ResourceKind.JSON, ResourceKind.FONT
            )
        }
        return GameInventory(
            pageUrl = pageUrl,
            assets = assets,
            notes = listOf(
                "Hanya resource yang tersedia & diizinkan.",
                "Logika server, RNG, dan validasi hasil tidak dapat disimpulkan dari aset frontend saja.",
                "Modul ini tidak untuk melewati autentikasi/DRM layanan pihak ketiga."
            )
        )
    }
}
