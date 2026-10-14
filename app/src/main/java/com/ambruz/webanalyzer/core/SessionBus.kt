package com.ambruz.webanalyzer.core

import org.json.JSONObject

/**
 * Bus sederhana agar PreviewRecordActivity bisa mengirim event
 * ke shell WebView (MainActivity) tanpa tight coupling.
 */
object SessionBus {
    @Volatile
    var listener: ((JSONObject) -> Unit)? = null

    fun emit(payload: JSONObject) {
        listener?.invoke(payload)
    }
}
