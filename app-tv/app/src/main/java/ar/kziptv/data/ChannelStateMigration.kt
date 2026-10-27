package ar.kziptv.data

import ar.kziptv.core.catalog.ChannelCatalog

/**
 * Traduce el estado guardado con IDs legacy a las claves canonicas.
 *
 * Los IDs legacy del grupo (`g:<hash>`) y del feed (`hash` de URL) cambiaban cuando
 * una playlist modificaba el nombre o la URL. Las claves canonicas no dependen de esos
 * datos, por lo que el refresh puede conservar favoritos, calidad y salud del canal.
 */
internal object ChannelStateMigration {
    fun migrate(
        state: PersistedState,
        newCatalog: ChannelCatalog,
        oldCatalog: ChannelCatalog? = null,
        rekeyed: Map<String, String> = emptyMap(),
    ): PersistedState {
        val favorites = state.favorites
            .map { groupKey(it, newCatalog, oldCatalog, rekeyed) }
            .distinct()

        val qualityPreference = state.qualityPreference.mapNotNull { (groupId, variantId) ->
            val group = groupKey(groupId, newCatalog, oldCatalog, rekeyed)
            val variant = variantKey(variantId, newCatalog, oldCatalog)
            val target = newCatalog.groupByStateKey[group]
            if (target != null && target.variants.any { it.stateKey == variant }) {
                group to variant
            } else {
                null
            }
        }.toMap()

        val variantStatus = state.variantStatus.mapNotNull { (variantId, status) ->
            val key = variantKey(variantId, newCatalog, oldCatalog)
            if (newCatalog.groupByVariantStateKey.containsKey(key)) key to status else null
        }.toMap()

        val measuredResolution = state.measuredResolution.mapNotNull { (variantId, resolution) ->
            val key = variantKey(variantId, newCatalog, oldCatalog)
            if (newCatalog.groupByVariantStateKey.containsKey(key)) key to resolution else null
        }.toMap()

        // Los numeros de canales eliminados se conservan como reserva: al volver a
        // aparecer, el canal recupera su numero.
        val channelNumbers = state.channelNumbers.mapNotNull { (groupId, number) ->
            val group = groupKey(groupId, newCatalog, oldCatalog, rekeyed)
            if (group.isNotBlank()) group to number else null
        }.toMap()

        val lastGroup = groupKey(state.lastGroupId, newCatalog, oldCatalog, rekeyed)
        val lastVariant = variantKey(state.lastChannelId, newCatalog, oldCatalog)

        return state.copy(
            favorites = favorites,
            qualityPreference = qualityPreference,
            variantStatus = variantStatus,
            measuredResolution = measuredResolution,
            channelNumbers = channelNumbers,
            lastGroupId = if (newCatalog.groupByStateKey.containsKey(lastGroup)) lastGroup else "",
            lastChannelId = if (newCatalog.groupByVariantStateKey.containsKey(lastVariant)) lastVariant else "",
        )
    }

    private fun groupKey(
        id: String,
        newCatalog: ChannelCatalog,
        oldCatalog: ChannelCatalog?,
        rekeyed: Map<String, String>,
    ): String {
        if (id.isBlank()) return ""

        // Ya es canonica: solo puede cambiar por un rekey explicito del diff.
        if (id.startsWith("c:")) return rekeyed[id] ?: id

        // Primero se resuelve contra el catalogo viejo: alli vive el ID legacy.
        val oldGroup = oldCatalog?.groupById?.get(id)
            ?: oldCatalog?.let { it.groupOf(id) }
            ?: oldCatalog?.groupByVariantStateKey?.get(id)
        if (oldGroup != null) {
            val mapped = rekeyed[oldGroup.stateKey] ?: oldGroup.stateKey
            return if (newCatalog.groupByStateKey.containsKey(mapped)) mapped else oldGroup.stateKey
        }

        val current = newCatalog.groupById[id]
            ?: newCatalog.groupOf(id)
            ?: newCatalog.groupByVariantStateKey[id]
        return current?.stateKey ?: id
    }

    private fun variantKey(
        id: String,
        newCatalog: ChannelCatalog,
        oldCatalog: ChannelCatalog?,
    ): String {
        if (id.isBlank()) return ""
        if (id.startsWith("v:")) return id

        val oldKey = oldCatalog?.variantStateKeyById?.get(id)
        if (oldKey != null) {
            // La variante se conserva si sigue presente con la misma identidad canonica.
            return if (newCatalog.groupByVariantStateKey.containsKey(oldKey)) oldKey else oldKey
        }

        return newCatalog.variantStateKeyById[id] ?: id
    }
}
