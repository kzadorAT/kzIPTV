package ar.kziptv.core.catalog

import ar.kziptv.core.model.Channel

class ChannelCatalog(channels: List<Channel>) {

    val all: List<Channel> = channels.sortedWith(compareBy({ it.groups.firstOrNull() ?: "" }, { it.name.lowercase() }))
    val byId: Map<String, Channel> = all.associateBy { it.id }
    val numbered: List<Channel> = all.mapIndexed { index, c -> c.copy(number = index + 1) }
    val categories: List<String> = all.flatMap { it.groups }.distinct().sorted()

    fun inCategory(category: String?): List<Channel> =
        if (category == null) numbered else numbered.filter { category in it.groups }

    fun search(query: String): List<Channel> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return numbered
        return numbered.filter {
            it.name.lowercase().contains(q) ||
                it.tvgId.lowercase().contains(q) ||
                it.groups.any { g -> g.lowercase().contains(q) }
        }
    }
}
