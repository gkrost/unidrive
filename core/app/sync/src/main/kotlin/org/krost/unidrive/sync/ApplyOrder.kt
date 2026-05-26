package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction

/**
 * Orders the apply-phase action list so parent-folder dependencies are respected:
 * a `CreateRemoteFolder` runs after the action that makes its parent path exist, and a
 * `MoveRemote` runs after its source path and its destination parent both exist. Without
 * this, the reconciler's discovery order can emit `mkdir-remote /A/B/C` before `mkdir-remote
 * /A/B`, or `mkdir-remote /Pictures/Scans` before `move /19notte78/Pictures -> /Pictures` —
 * both fail on the provider with "parent not found" / 409.
 */
fun topologicalApplyOrder(actions: List<SyncAction>): List<SyncAction> {
    // Only CreateRemoteFolder / MoveRemote affect the remote folder structure; everything else
    // keeps its absolute position. We reorder the structural actions among themselves (in
    // dependency order) and drop them back into the slots they originally occupied.
    val slots = actions.indices.filter { actions[it].isStructural() }
    if (slots.size < 2) return actions

    val structural = slots.map { actions[it] }

    // producer[path] = index (into `structural`) of the action that makes `path` exist.
    val producer = HashMap<String, Int>()
    structural.forEachIndexed { i, a ->
        val produced =
            when (a) {
                is SyncAction.CreateRemoteFolder -> normalizePath(a.path)
                is SyncAction.MoveRemote -> normalizePath(a.path)
                else -> null
            }
        if (produced != null) producer.putIfAbsent(produced, i)
    }

    // The in-batch producer that makes `required` exist: its exact producer, OR — failing that —
    // the closest ANCESTOR produced by a MoveRemote. A move relocates its whole subtree, so
    // `MoveRemote(/old/A -> /A)` makes not just /A but /A/B, /A/B/C, … available; a deep
    // `CreateRemoteFolder(/A/B/C)` whose immediate parent /A/B has no direct producer must still
    // depend on that move. A CreateRemoteFolder ancestor does NOT cover descendants (mkdir /A
    // creates only /A), so only MoveRemote ancestors satisfy a non-exact path.
    fun producerFor(required: String): Int? {
        producer[required]?.let { return it }
        var anc = parentPath(required)
        while (anc.isNotEmpty()) {
            val p = producer[anc]
            if (p != null && structural[p] is SyncAction.MoveRemote) return p
            anc = parentPath(anc)
        }
        return null
    }

    // prerequisites[i] = structural actions that must run before i (its parent / move source).
    val indegree = IntArray(structural.size)
    val dependents = Array(structural.size) { mutableListOf<Int>() }
    structural.forEachIndexed { i, a ->
        val required =
            when (a) {
                is SyncAction.CreateRemoteFolder -> listOf(parentPath(a.path))
                is SyncAction.MoveRemote -> listOf(normalizePath(a.fromPath), parentPath(a.path))
                else -> emptyList()
            }
        for (req in required) {
            val p = producerFor(req) ?: continue // req is pre-existing on the remote — no in-batch edge
            if (p != i) {
                dependents[p].add(i)
                indegree[i]++
            }
        }
    }

    // Kahn's algorithm with a stable ready-set ordered by original index, so an unconstrained
    // list comes back unchanged and ties stay deterministic.
    val ready = sortedSetOf<Int>().apply { (0 until structural.size).filter { indegree[it] == 0 }.forEach { add(it) } }
    val order = ArrayList<Int>(structural.size)
    while (ready.isNotEmpty()) {
        val n = ready.first()
        ready.remove(n)
        order.add(n)
        for (d in dependents[n]) {
            if (--indegree[d] == 0) ready.add(d)
        }
    }
    // Defensive: a cycle (not expected for tree paths) leaves nodes unplaced — append them in
    // original order rather than dropping or crashing.
    if (order.size < structural.size) {
        (0 until structural.size).filter { it !in order }.forEach { order.add(it) }
    }

    val result = actions.toMutableList()
    order.forEachIndexed { k, structuralIdx -> result[slots[k]] = structural[structuralIdx] }
    return result
}

private fun SyncAction.isStructural(): Boolean =
    this is SyncAction.CreateRemoteFolder || this is SyncAction.MoveRemote

private fun normalizePath(p: String): String {
    val s = if (p.startsWith("/")) p else "/$p"
    return if (s.length > 1) s.trimEnd('/') else s
}

private fun parentPath(p: String): String {
    val n = normalizePath(p)
    val idx = n.lastIndexOf('/')
    return if (idx <= 0) "" else n.substring(0, idx)
}
