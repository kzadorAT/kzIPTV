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
    val number: Int = 0,
    val stateKey: String = ""
) {
    val playable: Boolean get() = kind != KIND_UNSUPPORTED

    companion object {
        const val KIND_HLS = "hls"
        const val KIND_DASH = "dash"
        const val KIND_PROGRESSIVE = "progressive"
        const val KIND_UNSUPPORTED = "unsupported"
    }
}

/**
 * Un feed concreto de un canal: la misma señal puede estar publicada por varios
 * proveedores, en distintas resoluciones y/o regiones.
 */
@Serializable
data class ChannelVariant(
    val channelId: String,
    val name: String,
    val label: String = "",
    val quality: String = "",
    val region: String = "",
    val host: String = "",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val kind: String = Channel.KIND_HLS,
    val status: String = STATUS_UNKNOWN,
    /** Resolución REAL medida ("720x480"); el nombre del playlist suele mentir. */
    val measured: String = "",
    val stateKey: String = ""
) {
    val playable: Boolean get() = kind != Channel.KIND_UNSUPPORTED

    companion object {
        const val STATUS_UNKNOWN = "unknown"
        const val STATUS_OK = "ok"
        const val STATUS_DEAD = "dead"
        const val STATUS_SLOW = "slow"
    }
}

/**
 * Canal logico: agrupa los feeds-equivalentes de una misma señal.
 * Es lo que ve el usuario (una tarjeta por señal) y lo que se zaponea.
 */
@Serializable
data class ChannelGroup(
    val id: String,
    val name: String,
    val logo: String = "",
    val groups: List<String> = emptyList(),
    val number: Int = 0,
    val tvgId: String = "",
    val variants: List<ChannelVariant> = emptyList(),
    val activeVariantId: String = "",
    val status: String = ChannelVariant.STATUS_UNKNOWN,
    val stateKey: String = ""
) {
    val variantCount: Int get() = variants.size
    val hasVariants: Boolean get() = variants.size > 1
    val activeVariant: ChannelVariant?
        get() = variants.firstOrNull { it.channelId == activeVariantId }
}

/** Convierte una variante en un canal reproducible por el player. */
fun ChannelVariant.toChannel(): Channel = Channel(
    id = channelId,
    sourceId = "",
    tvgId = "",
    name = name,
    url = url,
    headers = headers,
    kind = kind,
    stateKey = stateKey
)

@Serializable
data class EpgProgram(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String = "",
    val category: String = ""
)
