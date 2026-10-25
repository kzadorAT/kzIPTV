package ar.kziptv.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Channel(
    val id: String,
    val sourceId: String,
    val tvgId: String = "",
    val name: String,
    val logo: String = "",
    val groups: List<String> = emptyList(),
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val kind: String = KIND_HLS,
    val number: Int = 0
) {
    val playable: Boolean get() = kind != KIND_UNSUPPORTED

    companion object {
        const val KIND_HLS = "hls"
        const val KIND_DASH = "dash"
        const val KIND_PROGRESSIVE = "progressive"
        const val KIND_UNSUPPORTED = "unsupported"
    }
}

@Serializable
data class EpgProgram(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String = "",
    val category: String = ""
)
