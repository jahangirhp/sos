package com.sos.nearby

/** RSSI is a proximity estimate, not a distance measurement. IDs survive BLE address rotation. */
internal class NearbyNodes(private val staleAfterMillis: Long = 30_000, private val capacity: Int = 256) {
    private data class Sample(val strength: Double, val seenAt: Long)
    private val nodes = mutableMapOf<String, Sample>()
    fun observe(id: String, rssi: Int, now: Long) {
        prune(now)
        if (rssi !in -127..0) return
        val old = nodes[id]
        if (old == null && nodes.size >= capacity) {
            val weakest = nodes.minByOrNull { it.value.strength } ?: return
            if (rssi <= weakest.value.strength) return
            nodes.remove(weakest.key)
        }
        nodes[id] = Sample(if (old == null) rssi.toDouble() else old.strength * 0.7 + rssi * 0.3, now)
    }
    fun closest(now: Long, limit: Int = 30): List<String> {
        require(limit in 0..30)
        prune(now)
        return nodes.entries.sortedWith(compareByDescending<Map.Entry<String, Sample>> { it.value.strength }
            .thenBy { it.key }).take(limit).map { it.key }
    }
    fun ids(now: Long): Set<String> { prune(now); return nodes.keys.toSet() }
    private fun prune(now: Long) { nodes.entries.removeAll { now - it.value.seenAt >= staleAfterMillis } }
}

/** Budget counts additional devices, excluding this phone. Every branch pays for its receiver first. */
internal class RecipientPlan(candidates: List<String>, budget: Int) {
    init { require(budget in 0..29) }
    val recipients: List<String> = candidates.distinct().take(budget)
    private val delivered = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()
    private val spare = budget - recipients.size
    val grants: Map<String, Int> = recipients.mapIndexed { index, id ->
        id to (spare / recipients.size + if (index < spare % recipients.size) 1 else 0)
    }.toMap()
    fun next(): String? = recipients.filter { it !in delivered && (attempts[it] ?: 0) < 2 }
        .minByOrNull { attempts[it] ?: 0 }
    fun attempted(id: String) { require(id in recipients); attempts[id] = (attempts[id] ?: 0) + 1 }
    fun delivered(id: String) { require(id in recipients); delivered.add(id) }
}
