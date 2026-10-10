package utils

/**
 * The texts of the help graph ([Graph.defaultGraph]), in markdown.
 * [start] is where a reader begins; [topics] is the tree around it.
 *
 * How the help graph is meant to look (Karel, 2026-10-10):
 *
 * - It is a cheatsheet, not documentation: a key and a few words per
 *   line, no explanations. Keys that do similar things share a line
 *   (`d` / `D` — delete / delete + reconnect). What needs sentences
 *   belongs in the README.
 * - A tree, drawn as a star with its root in the middle; not a grid.
 *   The root is "Quick start": the first thing a new user needs and
 *   no more. Its children, and theirs, get gradually more detailed.
 * - Around the root one small coloured node per category, naming it;
 *   the text of the category hangs on that, further out, and what is
 *   more special hangs on the text (marks on selection; modes and
 *   options on the command line).
 * - Every text node is markdown and starts with its own headline.
 * - Arguments are named, and what they can be is listed:
 *   `zf{motion}`, then `{motion}` — `t` / `T` / `l` / `L`.
 * - All the variants of a key are listed, never "and so on":
 *   `zi` / `zI`, `zo` / `zO`.
 * - A node that leads somewhere says how: "(press gf)".
 * - A new key or command gets a line in the node of its category; a
 *   new category gets a coloured node and a headline of its own.
 */
object Help {
    val start = """
        # Quick start

        - Double-click — new node / edit text
        - `Escape` — leave the editor
        - `v` — new node + edge from the selection
        - `e` — edge to the node under the cursor
        - Drag — move a node / pan
        - Wheel — zoom
        - `d` — delete
        - `u` / `U` — undo / redo
        - `Ctrl+S` — save
        - `:help` — more
    """.trimIndent()

    /** A node of the help: its text, and what hangs on it. One with a [link] is plain text that leads somewhere. */
    class Entry(text: String, val children: List<Entry> = emptyList(), val link: String? = null) {
        val text = text.trimIndent()
    }

    /** A category: the small coloured node that names it, and the tree that hangs on that. */
    class Topic(val name: String, val colour: Int, val entry: Entry)

    val topics = listOf(
        Topic(
            "Selection", 0xCFE2F3,
            Entry(
                """
                    # Selection

                    - Click / `Ctrl` + click / `Ctrl` + drag
                    - `Ctrl+A` / `Escape` — all / none
                    - `t` / `T` — add closure (fwd/bwd)
                    - `l` / `L` — add neighbours (fwd/bwd)
                    - `w` / `W` — remove oldest generation (fwd/bwd)
                    - `i` — invert
                    - `G` — go downstream
                """,
                listOf(
                    Entry(
                        """
                            # Marks

                            - `m{a-z}` / `'{a-z}` — set / recall mark
                            - `m+{a-z}` / `m-{a-z}` — add to / remove from mark
                            - `m{A-Z}` / `m+{A-Z}` / `m-{A-Z}` / `'{A-Z}` — saved with the file
                        """,
                    ),
                    Entry(
                        """
                            # Search

                            - `/{text}` / `?{text}` — search (fwd/bwd)
                            - `n` / `N` — next / previous
                        """,
                    ),
                ),
            ),
        ),
        Topic(
            "Editing", 0xF4C7C3,
            Entry(
                """
                    # Nodes & edges

                    - `v` / `V` — new node + edge (fwd/bwd)
                    - `e` / `E` — edge to node under cursor (fwd/bwd)
                    - `a` / `A` — append to last active (fwd/bwd)
                    - `c` — clear edges
                    - `d` / `D` — delete / delete + reconnect
                    - `Space` / `Shift+Space` — edit text
                """,
                listOf(
                    Entry(
                        """
                            # Style

                            - Right-click — colour / shape / size
                            - `F` / `f` — copy / paste style
                        """,
                    ),
                    Entry(
                        """
                            # History

                            - `u` / `Ctrl+Z` — undo
                            - `U` / `Ctrl+Shift+Z` / `Ctrl+R` — redo
                        """,
                    ),
                ),
            ),
        ),
        Topic(
            "Moving", 0xFCE8B2,
            Entry(
                """
                    # Moving

                    - Drag a node
                    - `g` / `s` — grab / scale
                    - `gx` / `gy` / `sx` / `sy` — along one axis only
                    - `:rotate` / `:mirror` / `:mirror-vertical`
                """,
                listOf(
                    Entry(
                        """
                            # Layout

                            - `o` / `O` — optimise (free / restricted)
                            - `-` / `=` — tighter / looser
                            - `:set dynamic`
                        """,
                    ),
                ),
            ),
        ),
        Topic(
            "View", 0xD9EAD3,
            Entry(
                """
                    # View

                    - Drag empty space / wheel — pan / zoom
                    - `0` / `z0` — fit
                    - `1` / `zz` / `z1` — centre
                    - `zt` / `zb` / `zs` / `ze` — to top / bottom / left / right edge
                """,
                listOf(
                    Entry(
                        """
                            # Hiding & folding

                            - `h` / `H` — hide / unhide one level
                            - `zm` / `zr` / `zR` — the same / unhide all
                            - `zf{motion}` / `zd{motion}` / `za{motion}`
                              — fold / unfold / toggle
                            - `{motion}` — `t` / `T` / `l` / `L`
                            - `zi` / `zI` — toggle children / all downstream
                            - `zo` / `zO` — toggle parents / all upstream
                        """,
                    ),
                    Entry(
                        """
                            # Depth & 3D

                            - `:z` / `:z {n}` / `:z +{n}` / `:z -{n}` / `:z ={n}` — depth
                            - `:set 3d` — middle button turns the view
                            - `:view-front`
                        """,
                    ),
                ),
            ),
        ),
        Topic(
            "Command line", 0xD0E0E3,
            Entry(
                """
                    # Command line

                    - `:` + `Tab` / `Shift+Tab` / `Ctrl+D` — complete
                    - `Up` / `Down` — history
                    - `:help` / `:help keys` / `:help commands` / `:help init`
                    - `:w` / `:wa` / `:wq` / `:e` / `:e!`
                    - `:q` / `:q!` / `:qa` / `:qa!`
                    - `:source {file}`
                """,
                listOf(
                    Entry(
                        """
                            # Modes

                            - normal — keys act on the graph
                            - edit — `Space` / double-click … `Escape`
                            - command line — `:` … `Enter` / `Escape`
                            - search — `/` / `?` … `Enter` / `Escape`
                            - transform — `g` / `s` … `g` / `s` / `Escape`
                        """,
                    ),
                    Entry(
                        """
                            # Options

                            - `:set {option}` / `:set no{option}` / `:set {option}!`
                            - `{option}`:
                              - `unhide` / `show-parents` / `show-children`
                              - `dynamic` / `discard` / `3d`
                            - `:set {name}={value}`
                            - `{name}`:
                              - `timeoutlen` / `picker`
                              - `spring-scale` / `spring-scale-step`
                        """,
                    ),
                    Entry(
                        """
                            # Mappings & macros

                            - `:map` / `:noremap` / `:remap` / `:unmap`
                            - `q{letter}` … `q` — record
                            - `@{letter}` — replay
                        """,
                    ),
                    Entry(
                        """
                            # Scripting

                            - `sane-graph-edit --attach` — join a window
                            - `sane-graph-edit ctl help`
                            - `sane-graph-edit {file} -e '{keys}'`
                        """,
                    ),
                ),
            ),
        ),
        Topic(
            "Files", 0xFFE599,
            Entry(
                """
                    # Files

                    - `Ctrl+S` / `Ctrl+Shift+S` — save / save as
                    - `:open` / `:e {path}` / `Ctrl+P` — open
                    - `F5` — reload
                    - `:browse` / `:cd {dir}` — file tree / workspace
                    - `Ctrl+E` / `Ctrl+Shift+E` — export SVG (all / selection)
                """,
                listOf(
                    Entry(
                        """
                            # Links

                            - `link="{link}"` — `gf` / `gF` (here / new tab)
                            - `link-{letter}="{link}"` — `g{letter}`
                            - `Ctrl+O` / `Ctrl+I` — jump back / forward
                            - `{link}`:
                              - `graph.dot` / `graph.dot:{nodeid}` / `:{nodeid}`
                              - `dir/`
                              - `A.kt` / `A.kt:10-20` / `README.md#Heading`
                              - `sge:help` / `sge:examples`
                        """,
                    ),
                    Entry(
                        """
                            # Tabs & clipboard

                            - `Ctrl+T` / `Ctrl+N` / `Ctrl+W` — new / new / close tab
                            - `gt` / `gT` — next / previous tab
                            - `Ctrl+C` / `Ctrl+X` / `Ctrl+V` — copy / cut / paste
                        """,
                    ),
                ),
            ),
        ),
        Topic(
            "Text of a node", 0xC9DAF8,
            Entry(
                """
                    # Text of a node

                    - `:content-type {type}`
                    - `{type}` — `plain` / `markdown` / `code` / `code-{language}`
                    - `:content-type {type}:{file}` — the text is that file
                    - `{file}` — `A.kt` / `A.kt:10-20` / `README.md#Heading`
                """,
            ),
        ),
        Topic(
            "About", 0xD9D9D9,
            Entry(
                """
                    # About

                    Created by Karel Tuček.

                    Code is freely available at
                    github.com/kareltucek/sane-graph-edit
                """,
                listOf(
                    // a client writes the examples out when it starts and knows where
                    Entry("examples\n(press gf)", link = "sge:examples"),
                    Entry(
                        """
                            # Todo

                            - menu bar
                            - port to compose multiplatform
                            - markdown editing in nodes
                            - cfg parser
                            - styles (y,p)
                            - folding?
                            - highlight related to selection (s?)
                        """,
                    ),
                ),
            ),
        ),
    )
}
