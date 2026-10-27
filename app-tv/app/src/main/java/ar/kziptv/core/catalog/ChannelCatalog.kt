package ar.kziptv.core.catalog

import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.ChannelGroup

/**
 * Catalogo de la app. El usuario ve canales LOGICOS (una tarjeta por señal);
 * cada canal logico tiene una o mas variantes (feeds/calidades) para reproducir.
 */
class ChannelCatalog(
    channels: List<Channel>,
    preferredNumbers: Map<String, Int> = emptyMap(),
) {

    /** Todos los feeds concretos, ordenados. */
    val all: List<Channel> = channels.sortedWith(
        compareBy({ it.groups.firstOrNull() ?: "" }, { it.name.lowercase() })
    )

    /** Feed concreto por id. */
    val byId: Map<String, Channel> = all.associateBy { it.id }

    /** Canales logicos (una tarjeta por señal). */
    private val grouped: List<ChannelGroup> = ChannelGrouper.group(all)

    /** Los canales existentes conservan su numero; los nuevos se agregan al final. */
    val numberAssignments: Map<String, Int> = assignNumbers(grouped, preferredNumbers)

    val groups: List<ChannelGroup> = grouped.map { group ->
        group.copy(number = numberAssignments[group.stateKey] ?: 0)
    }

    val groupById: Map<String, ChannelGroup> = groups.associateBy { it.id }

    /** Clave canonica -> canal logico. */
    val groupByStateKey: Map<String, ChannelGroup> = groups.associateBy { it.stateKey }

    /** Id concreto legacy -> clave canonica estable de la variante. */
    val variantStateKeyById: Map<String, String> = groups
        .flatMap { group -> group.variants.map { it.channelId to it.stateKey } }
        .toMap()

    /** Clave canonica de variante -> canal logico. */
    val groupByVariantStateKey: Map<String, ChannelGroup> = groups
        .flatMap { group -> group.variants.map { it.stateKey to group } }
        .toMap()

    /** Id de canal concreto -> id de canal logico. */
    val groupIdByVariantId: Map<String, String> = groups
        .flatMap { group -> group.variants.map { it.channelId to group.id } }
        .toMap()

    val categories: List<String> = groups.flatMap { it.groups }.distinct().sorted()

    val variantCount: Int = groups.sumOf { it.variantCount }

    /** Canales logicos de una categoria (o todos). */
    fun inCategory(category: String?): List<ChannelGroup> =
        if (category == null) groups else groups.filter { category in it.groups }

    private fun assignNumbers(
        groups: List<ChannelGroup>,
        preferred: Map<String, Int>,
    ): Map<String, Int> {
        val result = preferred.toMutableMap()
        val currentKeys = groups.map { it.stateKey }.toSet()
        val used = preferred.filterKeys { it !in currentKeys }.values
            .filter { it > 0 }
            .toMutableSet()
        var next = (preferred.values.maxOrNull() ?: 0) + 1
        for (group in groups) {
            val saved = preferred[group.stateKey]
            if (saved != null && saved > 0 && saved !in used) {
                result[group.stateKey] = saved
                used += saved
                continue
            }
            while (next in used) next++
            result[group.stateKey] = next
            used += next
            next++
        }
        return result
    }

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
