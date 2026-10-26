package ar.kziptv.data.room

import ar.kziptv.data.PersistedSource
import ar.kziptv.data.PersistedState

internal object RoomStateMapper {

    fun playlists(state: PersistedState): List<PlaylistEntity> =
        state.sources.mapIndexed { index, source ->
            PlaylistEntity(
                id = source.id,
                name = source.name,
                url = source.url,
                bundled = source.bundled,
                channelCount = source.channelCount,
                position = index,
                content = source.content,
            )
        }

    fun favorites(state: PersistedState): List<FavoriteEntity> =
        state.favorites.mapIndexed { index, channelId ->
            FavoriteEntity(channelId = channelId, position = index)
        }

    fun deviceTokens(state: PersistedState): List<DeviceTokenEntity> =
        state.tokens.mapIndexed { index, token ->
            DeviceTokenEntity(token = token, pairedAt = index.toLong())
        }

    fun appSettings(state: PersistedState): AppSettingsEntity =
        AppSettingsEntity(
            id = 1,
            pairingCode = state.pairingCode,
            zapFavoritesOnly = state.zapFavoritesOnly,
            lastChannelId = state.lastChannelId,
            lastGroupId = state.lastGroupId,
        )

    fun qualityPreferences(state: PersistedState): List<QualityPreferenceEntity> =
        state.qualityPreference.map { (groupId, variantId) ->
            QualityPreferenceEntity(groupId = groupId, variantId = variantId)
        }

    fun variantStates(state: PersistedState): List<VariantStateEntity> {
        val ids = (state.variantStatus.keys + state.measuredResolution.keys).sorted()
        return ids.map { variantId ->
            VariantStateEntity(
                variantId = variantId,
                status = state.variantStatus[variantId].orEmpty(),
                measuredResolution = state.measuredResolution[variantId].orEmpty(),
            )
        }
    }

    fun persistedState(
        playlists: List<PlaylistEntity>,
        favorites: List<FavoriteEntity>,
        tokens: List<DeviceTokenEntity>,
        settings: AppSettingsEntity?,
        qualityPreferences: List<QualityPreferenceEntity>,
        variantStates: List<VariantStateEntity>,
    ): PersistedState {
        val variantStatus = mutableMapOf<String, String>()
        val measuredResolution = mutableMapOf<String, String>()
        variantStates.forEach { row ->
            if (row.status.isNotBlank()) variantStatus[row.variantId] = row.status
            if (row.measuredResolution.isNotBlank()) {
                measuredResolution[row.variantId] = row.measuredResolution
            }
        }

        return PersistedState(
            sources = playlists.sortedBy { it.position }.map {
                PersistedSource(
                    id = it.id,
                    name = it.name,
                    url = it.url,
                    bundled = it.bundled,
                    channelCount = it.channelCount,
                    content = it.content,
                )
            },
            favorites = favorites.sortedBy { it.position }.map { it.channelId },
            pairingCode = settings?.pairingCode.orEmpty(),
            tokens = tokens.sortedWith(compareBy({ it.pairedAt }, { it.token })).map { it.token },
            lastChannelId = settings?.lastChannelId.orEmpty(),
            lastGroupId = settings?.lastGroupId.orEmpty(),
            zapFavoritesOnly = settings?.zapFavoritesOnly ?: false,
            qualityPreference = qualityPreferences.associate { it.groupId to it.variantId },
            variantStatus = variantStatus,
            measuredResolution = measuredResolution,
        )
    }
}
