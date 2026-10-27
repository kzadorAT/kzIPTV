package ar.kziptv.core.catalog

import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.ChannelGroup
import ar.kziptv.core.model.ChannelVariant

/** Diferencia entre dos versiones del catalogo de una playlist. */
data class PlaylistDiff(
    val added: List<ChannelGroup>,
    val removed: List<ChannelGroup>,
    val changed: List<ChannelGroup>,
    val rekeyed: Map<String, String>
)

object PlaylistDiffer {

    fun diff(oldChannels: List<Channel>, newChannels: List<Channel>): PlaylistDiff {
        val oldGroups = ChannelGrouper.group(oldChannels)
        val newGroups = ChannelGrouper.group(newChannels)
        val oldByKey = oldGroups.associateBy { it.stateKey }
        val newByKey = newGroups.associateBy { it.stateKey }
        val oldChannelsById = oldChannels.associateBy { it.id }
        val newChannelsById = newChannels.associateBy { it.id }

        val removed = oldGroups.filter { it.stateKey !in newByKey }
        val added = newGroups.filter { it.stateKey !in oldByKey }
        val changed = newGroups.filter { newGroup ->
            val oldGroup = oldByKey[newGroup.stateKey]
            oldGroup != null && groupChanged(oldGroup, newGroup, oldChannelsById, newChannelsById)
        }
        val rekeyed = matchRekeyed(removed, added, oldChannelsById, newChannelsById)

        return PlaylistDiff(
            added = added,
            removed = removed,
            changed = changed,
            rekeyed = rekeyed
        )
    }

    private fun groupChanged(
        oldGroup: ChannelGroup,
        newGroup: ChannelGroup,
        oldChannelsById: Map<String, Channel>,
        newChannelsById: Map<String, Channel>
    ): Boolean =
        oldGroup.name != newGroup.name ||
            oldGroup.logo != newGroup.logo ||
            oldGroup.groups.toSet() != newGroup.groups.toSet() ||
            semanticVariants(oldGroup, oldChannelsById) != semanticVariants(newGroup, newChannelsById)

    private data class VariantSemantics(
        val url: String,
        val kind: String,
        val label: String,
        val name: String,
        val quality: String,
        val region: String,
        val headers: Map<String, String>
    )

    private fun semanticVariants(
        group: ChannelGroup,
        channelsById: Map<String, Channel>
    ): Map<String, Set<VariantSemantics>> {
        return group.variants.groupBy { variantKey(group, it, channelsById) }
            .mapValues { (_, variants) ->
                variants.mapTo(mutableSetOf()) { variant ->
                    VariantSemantics(
                        url = variant.url,
                        kind = variant.kind,
                        label = variant.label,
                        name = variant.name,
                        quality = variant.quality,
                        region = variant.region,
                        headers = variant.headers
                    )
                }
            }
    }

    /** Empareja una clave anterior y nueva que conservaron al menos una variante. */
    private fun matchRekeyed(
        removed: List<ChannelGroup>,
        added: List<ChannelGroup>,
        oldChannelsById: Map<String, Channel>,
        newChannelsById: Map<String, Channel>
    ): Map<String, String> {
        val oldVariantKeys = removed.associate { group ->
            group.stateKey to group.variants.mapTo(mutableSetOf()) {
                variantKey(group, it, oldChannelsById)
            }
        }
        val newVariantKeys = added.associate { group ->
            group.stateKey to group.variants.mapTo(mutableSetOf()) {
                variantKey(group, it, newChannelsById)
            }
        }

        val oldKeys = oldVariantKeys.keys.sorted()
        val newKeys = newVariantKeys.keys.sorted()
        val candidates = oldKeys.associateWith { oldKey ->
            newKeys.filter { newKey ->
                oldVariantKeys[oldKey].orEmpty().intersect(newVariantKeys[newKey].orEmpty()).isNotEmpty()
            }.sortedWith(
                compareByDescending<String> { newKey ->
                    oldVariantKeys[oldKey].orEmpty().intersect(newVariantKeys[newKey].orEmpty()).size
                }.thenBy { it }
            )
        }

        // Emparejamiento bipartito maximo; el orden fija el desempate.
        val matchedNewToOld = mutableMapOf<String, String>()
        fun assign(oldKey: String, visited: MutableSet<String>): Boolean {
            for (newKey in candidates[oldKey].orEmpty()) {
                if (!visited.add(newKey)) continue
                val previousOld = matchedNewToOld[newKey]
                if (previousOld == null || assign(previousOld, visited)) {
                    matchedNewToOld[newKey] = oldKey
                    return true
                }
            }
            return false
        }

        for (oldKey in oldKeys.sortedBy { candidates[it].orEmpty().size }) {
            assign(oldKey, mutableSetOf())
        }

        return matchedNewToOld.entries
            .sortedBy { it.key }
            .associateTo(linkedMapOf()) { (newKey, oldKey) -> oldKey to newKey }
    }

    /** Usa la clave persistida y, si falta, deriva una clave estable. */
    private fun variantKey(
        group: ChannelGroup,
        variant: ChannelVariant,
        channelsById: Map<String, Channel>
    ): String {
        if (variant.stateKey.isNotBlank()) return variant.stateKey
        val channel = channelsById[variant.channelId]
        if (channel != null) {
            if (channel.stateKey.isNotBlank()) return channel.stateKey
            return ChannelIdentity.variantKey(channel.sourceId, channel.tvgId, channel.name)
        }
        return ChannelIdentity.variantKey("", group.tvgId, variant.name)
    }
}
