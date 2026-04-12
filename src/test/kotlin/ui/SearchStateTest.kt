package ui

import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchStateTest {

    // --- smartcase matching ---

    @Test
    fun `lowercase query is case-insensitive`() {
        assertTrue(SearchState.matches("Hello World", "hello"))
        assertTrue(SearchState.matches("HELLO", "hello"))
        assertTrue(SearchState.matches("hello", "hello"))
    }

    @Test
    fun `query with uppercase is case-sensitive`() {
        assertTrue(SearchState.matches("Hello", "Hello"))
        assertFalse(SearchState.matches("hello", "Hello"))
        assertFalse(SearchState.matches("HELLO", "Hello"))
    }

    @Test
    fun `empty query matches nothing`() {
        assertFalse(SearchState.matches("anything", ""))
    }

    @Test
    fun `substring match not just prefix`() {
        assertTrue(SearchState.matches("abcdef", "cde"))
    }

    // --- cursor navigation ---

    @Test
    fun `next cycles through results`() {
        val ss = SearchState()
        val a = Node("A", Vector2(0, 0))
        val b = Node("B", Vector2(100, 0))
        val c = Node("C", Vector2(200, 0))
        ss.results = listOf(a, b, c)
        ss.cursor = -1

        assertEquals(a, ss.next())  // -1 → 0
        assertEquals(b, ss.next())  // 0 → 1
        assertEquals(c, ss.next())  // 1 → 2
        assertEquals(a, ss.next())  // 2 → 0 (wrap)
    }

    @Test
    fun `prev cycles backwards`() {
        val ss = SearchState()
        val a = Node("A", Vector2(0, 0))
        val b = Node("B", Vector2(100, 0))
        ss.results = listOf(a, b)
        ss.cursor = 0

        assertEquals(b, ss.prev())  // 0 → 1 (wraps to end)
        assertEquals(a, ss.prev())  // 1 → 0
    }

    @Test
    fun `next on empty results returns null`() {
        val ss = SearchState()
        ss.results = emptyList()
        assertNull(ss.next())
    }

    @Test
    fun `prev on empty results returns null`() {
        val ss = SearchState()
        ss.results = emptyList()
        assertNull(ss.prev())
    }
}
