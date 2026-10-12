package com.getvideo.app.data.local

/** Remote metadata cannot change a local edit's file, duration, hash or title. */
internal fun mergeServerTrack(fresh: TrackEntity, existing: TrackEntity?, now: Long): TrackEntity {
    if (existing == null) return fresh.copy(addedAtMs = now)
    if (existing.locallyEdited) return existing.copy(updatedSeq = fresh.updatedSeq)
    return fresh.copy(
        addedAtMs = existing.addedAtMs.takeIf { it > 0 } ?: now,
        localPath = existing.localPath.takeIf { existing.audioSha256 == fresh.audioSha256 },
        favorite = existing.favorite,
        localCategoryId = existing.localCategoryId,
        title = existing.localTitleOverride ?: fresh.title,
        localTitleOverride = existing.localTitleOverride,
        updatedAtMs = if (existing.localTitleOverride != null) maxOf(existing.updatedAtMs, fresh.updatedAtMs)
            else if (existing.updatedSeq == fresh.updatedSeq && existing.updatedAtMs > 0) existing.updatedAtMs else fresh.updatedAtMs,
    )
}
