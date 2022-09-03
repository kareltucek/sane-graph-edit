package ui

import utils.Constants
import Graph
import Node
import graph_tools.Plotter
import org.jetbrains.annotations.Nullable
import ui.Utils.orElse
import utils.Vector2
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import java.time.Instant


object Clicker {
    fun Vector2.isInSquare(lt: Vector2, br: Vector2, tolerance: Double): Boolean {
        val xMatches = lt.x - tolerance < this.x && this.x < br.x + tolerance
        val yMatches = lt.y - tolerance < this.y && this.y < br.y + tolerance
        return xMatches && yMatches
    }

    fun selectClickedNode(g: Graph, clickCoordinates: Vector2): MutableSet<Node> {
        return g.nodes
            .filter {
                it.cache?.shapeBounds?.let { bounds ->
                    clickCoordinates.isInSquare(
                        it.position - bounds / 2,
                        it.position + bounds / 2,
                        10.0 / Plotter.t.scaleX.toDouble()
                    )
                }.orElse(false)
            }
            .minByOrNull { (it.position - clickCoordinates).lengthSquared() }
            ?.let { mutableSetOf(it) }
            ?: mutableSetOf()
    }
}

object Utils {
    fun MouseEvent.toScreenspaceVector(): Vector2 = Vector2(this.x.toDouble(), this.y.toDouble())
    fun MouseEvent.toWorkspaceVector(): Vector2 {
        var pt = Point2D.Double(this.x.toDouble() + Constants.frameMargin, this.y.toDouble() + Constants.frameMargin)
        var res = Plotter.t.inverseTransform(pt, null)
        return Vector2(res.x.toDouble(), res.y.toDouble())
    }

    fun Vector2.toWorkspaceVector(): Vector2 {
        var pt = Point2D.Double(
            this.x.toDouble() + utils.Constants.frameMargin,
            this.y.toDouble() + utils.Constants.frameMargin
        )
        var res = Plotter.t.inverseTransform(pt, null)
        return Vector2(res.x.toDouble(), res.y.toDouble())
    }

    fun Vector2.toScreenVector(): Vector2 {
        var pt = Point2D.Double(this.x.toDouble(), this.y.toDouble())
        var res = Plotter.t.transform(pt, null)
        return Vector2(res.x.toDouble() - Constants.frameMargin, res.y.toDouble() - Constants.frameMargin)
    }

    fun Vector2.workspaceSizeTransform(): Vector2 {
        return this * Plotter.t.scaleX.toDouble()
    }

    fun <T> T?.orElse(t: T): T = this ?: t

    fun <T> T?.orElse(t: () -> T): T = this ?: t()
    fun <T> T.exhaustive(): T = this
    fun <K, V> Map<K, V>.inverseMap() = map { Pair(it.value, it.key) }.toMap().toMutableMap()

    fun <T : Any> T.letIf(condition: Boolean, f: (T) -> T): T = if (condition) f(this) else this

    fun <T> T?.isNotNull() = this != null

    fun <T> Boolean.fold(onZero: T, onOne: T): T = if (this) onOne else onZero


    fun fromHexString(s: String): Color {
        return s.replace("#", "")
            .chunked(2)
            .map { Integer.valueOf(it, 16) }
            .let {
                Color(
                    it[0].orElse(0),
                    it[1].orElse(0),
                    it[2].orElse(0)
                )
            }
    }

    fun Color.toHexString(): String {
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

fun createAndShowGUI() {
    val frame = Window("Sane Graph Edit")
    frame.isVisible = true
}

fun main(args: Array<String>) {
    println("Program arguments: ${args.joinToString()}")
    EventQueue.invokeLater(::createAndShowGUI)
}