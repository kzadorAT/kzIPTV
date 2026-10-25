package ar.kziptv.core.catalog

import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.ChannelGroup

/**
 * Catalogo de la app. El usuario ve canales LOGICOS (una tarjeta por señal);
 * cada canal logico tiene una o mas variantes (feeds/calidades) para reproducir.
 */
class ChannelCatalog(channels: List<Channel>) {

    /** Todos los feeds concretos, ordenados. */
    val all: List<Channel> = channels.sortedWith(
        compareBy({ it.groups.firstOrNull() ?: "" }, { it.name.lowercase() })
    )

    /** Feed concreto por id. */
    val byId: Map<String, Channel> = all.associateBy { it.id }

    /** Canales logicos (una tarjeta por señal), numerados 1..N. */
    val groups: List<ChannelGroup> = ChannelGrouper.group(all)
        .mapIndexed { index, group -> group.copy(number = index + 1) }

    val groupById: Map<String, ChannelGroup> = groups.associateBy { it.id }

    /** Id de canal concreto -> id de canal logico. */
    val groupIdByVariantId: Map<String, String> = groups
        .flatMap { group -> group.variants.map { it.channelId to group.id } }
        .toMap()

    val categories: List<String> = groups.flatMap { it.groups }.distinct().sorted()

    val variantCount: Int = groups.sumOf { it.variantCount }

    /** Canales logicos de una categoria (o todos). */
    fun inCategory(category: String?): List<ChannelGroup> =
        if (category == null) groups else groups.filter { category in it.groups }

    /** Busca por nombre, tvg-id o categoria. */
    fun search(query: String): List<ChannelGroup> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return groups
        return groups.filter {
            it.name.lowercase().contains(q) ||
                it.tvgId.lowercase().contains(q) ||
                it.variants.any { v -> v.name.lowercase().contains(q) } ||
                it.groups.any { g -> g.lowercase().contains(q) }
        }
    }

    /** Resuelve un id de canal logico o de feed concreto. */
    fun resolve(id: String): ChannelGroup? =
        groupById[id] ?: groupIdByVariantId[id]?.let { groupById[it] }

    /** Busca el canal logico de un feed concreto. */
    fun groupOf(variantId: String): ChannelGroup? =
        groupIdByVariantId[variantId]?.let { groupById[it] }
}
