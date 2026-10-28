package com.getvideo.app.player

/** A permutation only: reject stale snapshots so drag cannot add, delete or duplicate videos. */
internal fun queueReorderMoves(current: List<Long>, desired: List<Long>): List<Pair<Int, Int>>? {
    if (current.size != desired.size || current.toSet().size != current.size ||
        desired.toSet() != current.toSet()) return null
    val working = current.toMutableList()
    return buildList {
        desired.forEachIndexed { target, id ->
            val source = working.indexOf(id)
            if (source != target) {
                add(source to target)
                working.add(target, working.removeAt(source))
            }
        }
    }
}
