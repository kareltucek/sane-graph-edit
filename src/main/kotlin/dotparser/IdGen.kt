package dotparser

import Utils.orElse

class IdGen {
    val counters: MutableMap<String, Long> = mutableMapOf()
    fun new(prefix: String): String {
        if (!counters.containsKey(prefix)) {
            counters[prefix] = 0
        }
        val id = counters[prefix].orElse(0)
        counters[prefix] = id + 1
        return prefix + id.toString()
    }
}