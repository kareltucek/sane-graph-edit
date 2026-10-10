package utils

/**
 * The texts of the help graph ([Graph.defaultGraph]), in markdown.
 * [commands], [selection] and [files] are the reference written by
 * hand, the first of them being where a reader starts; [topics] are
 * what hangs around it: a category and what there is to say of it,
 * kept brief.
 */
object Help {
    val commands = """
        # Graph manipulation

        **Mouse**
        - Double-click empty — new node
        - Double-click node — edit text
        - Drag a node — move the selection
        - Drag empty space — pan view
        - Mouse wheel — zoom (centred on cursor)
        - Right-click — colour / shape / size picker

        **Keys**
        - `v` / `V` + mouseover — new node + edge from selection (fwd/bwd)
        - `e` / `E` + mouseover — connect selection to node under cursor (fwd/bwd)
        - `a` / `A` — append new node + edge from last active (fwd/bwd)
        - `c` — clear edges (within or incident to selection)
        - `d` — delete selected nodes
        - `D` — delete + reconnect predecessors to successors
        - `o` / `O` — layout optimiser (free / restricted)
        - `-` / `=` — optimize + shrink / grow spring target distance
        - `g` — toggle grab-and-move

        **Style**
        - `F` — copy style from selected node
        - `f` — paste style onto selected nodes

        **History**
        - `u` / `Ctrl+Z` — undo
        - `U` / `Ctrl+Shift+Z` — redo (`Ctrl+R` also works)

        The coloured nodes around have more.
    """.trimIndent()

    val selection = """
        # Selection & navigation

        **Mouse**
        - Click node — select (replaces current selection)
        - `Ctrl` + click node — toggle in / out of multi-selection
        - `Ctrl` + drag empty — rubber-band selection box

        **Keys**
        - `Ctrl+A` — select all / deselect all (toggle)
        - `Escape` — clear selection
        - `t` / `T` — add forward/backward reachable closure
        - `l` / `L` — add one generation of neighbours
        - `w` / `W` — remove the "oldest" generation of selection
        - `Space` — edit text of last active node
        - `Shift+Space` — replace selection with last active, then edit

        **Filtering**
        - `i` — invert selection (among visible nodes)
        - `h` — hide the selected nodes
        - `H` — unhide one level (undo the last `h`)
        - `ih` — invert then hide: keep only the selection

        **Marks**
        - `ma`..`mz` — save selection under mark a..z (session only)
        - `mA`..`mZ` — save selection under mark A..Z (persisted to file)
        - `m+a` / `m-a` — add the selection to mark a / take it out
        - `'a` — recall mark a

        **View**
        - `0` — fit view to all visible (or to selection)
        - `1` — centre view on selection
    """.trimIndent()

    val files = """
        # Files, tabs & clipboard

        **Files**
        - `:open` — open (file dialog)
        - `Ctrl+O` / `Ctrl+I` — jump back / forward (after `gf` etc.)
        - `F5` / `:e` — reload file from disk
        - `gf` / `gF` — follow the node's file link (here / new tab)
        - `g<letter>` — follow the node's `link-<letter>`
        - `:browse` — file tree of the working dir (`gf` opens); also `:tabnew`
        - `:cd <dir>` — switch the workspace (working dir + its tabs)
        - `Ctrl+S` — save
        - `Ctrl+Shift+S` — save as
        - `Ctrl+E` — export SVG
        - `Ctrl+Shift+E` — export selection as SVG

        **Tabs**
        - `Ctrl+N` / `Ctrl+T` — open file in new tab
        - `Ctrl+W` — close tab (prompt if dirty)
        - `Ctrl+Tab` / `Ctrl+Shift+Tab` — next / previous tab
        - `Ctrl+PgDn` / `Ctrl+PgUp` — next / previous tab (alias)

        **Clipboard**
        - `Ctrl+C` / `Ctrl+X` / `Ctrl+V` — copy / cut / paste (works across tabs)
    """.trimIndent()

    val attribution = """
        Created by Karel Tuček.

        Code is freely available at
        github.com/kareltucek/sane-graph-edit
    """.trimIndent()

    val todo = """
        # Todo

        - menu bar
        - port to compose multiplatform
        - markdown editing in nodes
        - cfg parser
        - styles (y,p)
        - folding?
        - highlight related to selection (s?)
    """.trimIndent()

    /** A category: the small coloured node that names it, and what hangs on that. */
    class Topic(val name: String, val colour: Int, val text: String)

    val topics = listOf(
        Topic(
            "Moving", 0xF4C7C3,
            """
                - `g`, `s` — grab / scale the selection with the pointer; again ends it, `Escape` cancels
                - `x` / `y` — meanwhile: along that axis only
                - `:rotate`, `:mirror`, `:mirror-vertical`
                - `:set dynamic` — a node that becomes visible is put beside its neighbours
            """,
        ),
        Topic(
            "Search", 0xFCE8B2,
            """
                - `/text`, `?text` — search forward / backward
                - `n` / `N` — next / previous match
                - `G` — go down: select what is downstream instead, fit the view
            """,
        ),
        Topic(
            "Folding & view", 0xD9EAD3,
            """
                - `zf` / `zd` / `za` + `t` `T` `l` `L` — fold / unfold / toggle what that key would select
                - `zi` `zI` / `zo` `zO` — toggle children, all downstream / parents, all upstream
                - `zR` — unhide everything
                - `zz`, `zt`, `zb`, `zs`, `ze` — selection to the centre / an edge of the view
                - `:set unhide`, `show-parents`, `show-children` — what else is shown
            """,
        ),
        Topic(
            "Text of a node", 0xC9DAF8,
            """
                - `:content-type markdown` — formatted; mermaid and dot blocks are drawn
                - `:content-type code-<language>` — highlighted code; `plain` — back
                - `:content-type code-kotlin:src/A.kt:10-20` — the text is those lines of that file
                  (also `README.md#Heading`, or a whole file); `:w` writes the file too
            """,
        ),
        Topic(
            "Depth & 3D", 0xD9D2E9,
            """
                - `:z 300`, `:z +50`, `:z -50` — put the selection at a depth / move it away / nearer
                - the wheel over a node goes towards that node
                - `:set 3d` — the middle button turns the view; `:view-front` turns it back
            """,
        ),
        Topic(
            "Links", 0xF6EFC4,
            """
                A node attribute `link="..."` (`gf`) or `link-<letter>="..."` (`g<letter>`):
                - `graph.dot`, `graph.dot:<nodeid>`, `:<nodeid>` — a graph, a node of it, a node here
                - `dir/` — its file tree
                - `notes.txt`, `A.kt:10-20`, `README.md#Heading` — text, opened for editing
                - `sge:help`, `sge:examples`
            """,
        ),
        Topic(
            "Command line", 0xD0E0E3,
            """
                - `:` then `Tab` completes, `Ctrl+D` lists, `Up` / `Down` history
                - `:help keys`, `:help commands`, `:help init` — everything there is
                - `:w`, `:wa`, `:e`, `:e!`, `:q`, `:qa`, `:wq`, `:export svg <path>`
                - `:map <keys> <keys>`, `:unmap`, `:set`, `:source <file>`
                - `q<letter>` records a macro (`q` ends it), `@<letter>` replays it
            """,
        ),
        Topic(
            "Sessions & scripting", 0xEAD1DC,
            """
                - open tabs are remembered per directory; backups go to `~/.cache/sane-graph-edit/backups`
                - `sane-graph-edit --attach` — join the window running in this directory
                - `sane-graph-edit ctl help` — read and change its graphs from a script
                - `sane-graph-edit <file> -e '<keys>'` — run keys without a window
            """,
        ),
    )
}
