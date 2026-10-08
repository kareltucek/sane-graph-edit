package utils

import java.time.Instant

object Utils {

    fun Double.format(digits: Int) = "%.${digits}f".format(this)

    fun Double.toNiceString(): String {
        return this.format(1)
    }

    fun <T> T?.orElse(t: T): T = this ?: t

    fun <T> T?.orElse(t: () -> T): T = this ?: t()
    fun <T> T.exhaustive(): T = this
    fun <K, V> Map<K, V>.inverseMap() = map { Pair(it.value, it.key) }.toMap().toMutableMap()

    fun <T : Any> T.letIf(condition: Boolean, f: (T) -> T): T = if (condition) f(this) else this

    fun <T> T?.isNotNull() = this != null

    fun <T> Boolean.fold(onZero: T, onOne: T): T = if (this) onOne else onZero


    fun fromHexString(s: String): Rgb {
        return s.replace("#", "")
            .chunked(2)
            .map { Integer.valueOf(it, 16) }
            .let {
                Rgb(
                    it[0].orElse(0),
                    it[1].orElse(0),
                    it[2].orElse(0)
                )
            }
    }

    fun Boolean.toUnit(): Unit? {
        return if (this) {
            Unit
        } else {
            null
        }
    }

    fun Rgb.toHexString(): String {
        fun byteToHex(b: Int): String {
            val s = "00" + Integer.toHexString(b)
            return s.substring(s.length - 2, s.length)
        }
        return "#${byteToHex(this.red)}${byteToHex(this.green)}${byteToHex(this.blue)}"
    }

    @Suppress("UNCHECKED_CAST")
    fun <K, V> Map<K, V?>.filterNotNull(): Map<K, V> = this.filter { it.value != null } as Map<K, V>
    class CachedMap<K, V>(
        val f: (K) -> V = { _ -> throw Throwable("creation function not specified") },
    ) {
        val map: MutableMap<K, V> = mutableMapOf()
        operator fun get(k: K): V {
            return map[k].orElse {
                val newElement = f(k)
                map[k] = newElement
                newElement
            }
        }

        operator fun get(k: K, v: () -> V): V {
            return map[k].orElse {
                val newElement = v()
                map[k] = newElement
                newElement
            }
        }
    }

    object PerformanceData {
        val avgTimes: MutableMap<String, Double> = mutableMapOf()
        fun withPerformanceCheck(
            id: String,
            limit: Double,
            updateWeight: Double = 1.0,
            onIssue: () -> Unit = {},
            f: () -> Unit
        ) {
            val watchStart = Instant.now()

            f()

            val watchEnd = Instant.now()

            val avgTime =
                (avgTimes[id].orElse(0.0) * (1 - updateWeight) + (watchEnd.toEpochMilli() - watchStart.toEpochMilli()) * updateWeight)
            avgTimes[id] = avgTime
            if (avgTime > limit) {
                println("Performance watch '$id' detected average time of $avgTime!")
                onIssue()
                avgTimes[id] = 0.0
            }
        }
    }
}
