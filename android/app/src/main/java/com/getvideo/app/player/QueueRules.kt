package com.getvideo.app.player

import com.getvideo.app.data.local.TrackEntity

/** ID protects renamed entries; hash and source identify the same audio across imports. */
internal fun newQueueTracks(existing: List<TrackEntity>, incoming: List<TrackEntity>): List<TrackEntity> {
    val ids = existing.map { it.id }.toMutableSet()
    val hashes = existing.mapNotNull { it.audioSha256?.trim()?.takeIf(String::isNotEmpty)?.lowercase() }.toMutableSet()
    val sources = existing.filter { it.videoId.isNotBlank() }.map { it.platform to it.videoId }.toMutableSet()
    return incoming.filter { track ->
        val hash = track.audioSha256?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
        val source = (track.platform to track.videoId).takeIf { track.videoId.isNotBlank() }
        val fresh = track.id !in ids && (hash == null || hash !in hashes) && (source == null || source !in sources)
        ids.add(track.id)
        if (hash != null) hashes.add(hash)
        if (source != null) sources.add(source)
        fresh
    }
}

internal fun queueRemovalIndices(ids: List<Long>, deletedIds: Set<Long>): List<Int> =
    ids.indices.filter { ids[it] in deletedIds }.reversed()
