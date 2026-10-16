package com.wb.signalpro.data.repository

import com.wb.signalpro.data.model.ContextId
import com.wb.signalpro.data.model.ContextState
import com.wb.signalpro.data.model.Episode
import com.wb.signalpro.data.model.EpisodeId
import com.wb.signalpro.data.model.EventId
import com.wb.signalpro.data.model.MediaId
import com.wb.signalpro.data.model.MediaItem
import com.wb.signalpro.data.model.SignalEvent

interface MediaRepository {
    suspend fun get(id: MediaId): MediaItem?

    suspend fun get(ids: Collection<MediaId>): List<MediaItem>

    suspend fun list(limit: Int = 2_000, offset: Int = 0): List<MediaItem>

    suspend fun upsert(items: Collection<MediaItem>)
}

interface EventRepository {
    suspend fun get(id: EventId): SignalEvent?

    suspend fun append(events: Collection<SignalEvent>)
}

interface EpisodeRepository {
    suspend fun get(id: EpisodeId): Episode?

    suspend fun upsert(episodes: Collection<Episode>)
}

interface ContextRepository {
    suspend fun get(id: ContextId): ContextState?

    suspend fun upsert(contextStates: Collection<ContextState>)
}

/** Persistence boundary exposed to higher layers. */
interface SignalDataStore {
    val media: MediaRepository
    val events: EventRepository
    val episodes: EpisodeRepository
    val contexts: ContextRepository
}
