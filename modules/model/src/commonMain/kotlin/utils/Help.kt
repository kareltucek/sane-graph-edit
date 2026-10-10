package utils

/**
 * The texts of the help graph ([Graph.defaultGraph]): one node per
 * category, around [hub]. A feature that gets a key or a command
 * gets a line in the node of its category.
 */
object Help {
    val hub = """
        sane-graph-edit
        ===============

        One node per category around this one.

        Keys act on the selection, some on the
        node under the mouse pointer.

        0             - see everything
        click, then 1 - go to a node
        Ctrl+O        - back to where gf was pressed

        :help          - what the : bar can tell
        :help keys     - every key as it is bound now
        :help commands - every command
        :help init     - the init file and the settings
    """.trimIndent()

    val mouse = """
        Mouse
        =====

        Click node         - select it alone
        Ctrl + click node  - add to / take out of the selection
        Ctrl + drag empty  - selection box
        Drag a node        - move the selection
        Drag empty space   - pan the view
        Wheel              - zoom at the pointer
                             (over a node: towards that node)
        Double-click empty - new node
        Double-click node  - edit its text
        Right-click        - colour / shape / size picker
        Middle drag        - turn the view (3D mode only)
    """.trimIndent()

    val structure = """
        Nodes & edges
        =============

        v / V - new node at the pointer, edge from / to the selection
        a / A - new node appended to the last active one (fwd / bwd)
        e / E - edge from / to the selection, to the node
                under the pointer
        c     - clear edges (within the selection, or its own)
        d     - delete the selected nodes
        D     - delete, and join their predecessors to
                their successors
    """.trimIndent()

    val moving = """
        Moving & layout
        ===============

        g           - grab: the selection follows the pointer;
                      g again ends it, Escape cancels
        s           - scale the selection's positions the same way
        x / y       - while grabbing or scaling: only along that
                      axis (again: free)
        :rotate     - turn the selection with the pointer
        :mirror     - mirror it left to right
        :mirror-vertical - mirror it top to bottom

        o / O       - layout optimiser (free / around the grabbed node)
        - / =       - optimise with shorter / longer edges
        :set dynamic - a node that becomes visible is put beside
                      its visible neighbours
    """.trimIndent()

    val selection = """
        Selection
        =========

        Ctrl+A - all / none
        Escape - none
        i      - invert (among the visible nodes)
        t / T  - add everything downstream / upstream
        l / L  - add the children / the parents
        w / W  - take out the oldest generation
        G      - everything downstream of the selection instead
                 of it, view fitted to it

        /text  - search forward, Enter selects the match
        ?text  - search backward
        n / N  - next / previous match
    """.trimIndent()

    val marks = """
        Marks
        =====

        A mark is a letter on any number of nodes;
        a node can have several.

        ma  - mark a is exactly the selection
        m+a - add the selection to mark a
        m-a - take the selection out of mark a
        'a  - select the visible nodes that have mark a

        a..z - for this session only
        A..Z - saved with the file (marks="AB")
    """.trimIndent()

    val folding = """
        Hiding & folding
        ================

        h / zm - hide the selection
        H / zr - unhide one level (the last h)
        ih     - keep only the selection
        zR     - unhide everything

        zf{motion} - fold: hide what the motion reaches
        zd{motion} - unfold
        za{motion} - toggle
                     motion: t T l L
        zi / zI    - toggle children / everything downstream
        zo / zO    - toggle parents / everything upstream

        :set unhide        - t T l L G also unhide what they reach
        :set show-parents  - also show parents of what is shown
        :set show-children - also show children of what is shown
    """.trimIndent()

    val view = """
        View
        ====

        0 / z0      - fit everything visible, or the selection
        1 / zz / z1 - centre on the selection
        zt / zb     - selection to the top / bottom edge
        zs / ze     - selection to the left / right edge
        Wheel       - zoom
        Drag empty  - pan

        Without a selection the z keys act on the
        last active node.
    """.trimIndent()

    val text = """
        Text & content types
        ====================

        Space        - edit the last active node
        Shift+Space  - select it alone, then edit
        Double-click - edit that node
        Escape       - leave the editor
        (Electron client: the editor is neovim)

        :content-type          - tell what the selection's text is
        :content-type markdown - formatted; mermaid and dot
                                 blocks are drawn as diagrams
        :content-type code-<language> - highlighted code
        :content-type code | plain

        Text kept in a file: a type followed by : and a link
          code-kotlin:src/A.kt:10-20   lines
          markdown:README.md#Depth     a section
          plain:notes.txt              all of it
        Such a node shows the file's text; an edit is a change of
        the graph (undo works) and :w writes the file too.
    """.trimIndent()

    val depth = """
        Depth & 3D
        ==========

        :z       - tell the depth of the selection
        :z 300   - put it at that depth (away from you)
        :z +50   - further away by that much
        :z -50   - nearer by that much
        :z =-300 - at a negative depth (in front of the plane)

        What is further away is drawn smaller. The wheel over
        a node goes towards that node.

        :set 3d     - 3D mode ([3d] in the status line):
                      the middle button turns the view around
                      the selection
        :view-front - turn it back
        :set no3d   - back to 2D
    """.trimIndent()

    val style = """
        Style
        =====

        Right-click - colour / shape / size picker
        F - copy the style of the selected node
        f - paste it onto the selection
    """.trimIndent()

    val history = """
        History
        =======

        u / Ctrl+Z                - undo
        U / Ctrl+Shift+Z / Ctrl+R - redo

        Every change of the graph is a step: a drag is one,
        an edit of a text is one. Selection and view are not.
        :e (reload) is a step too.
    """.trimIndent()

    val files = """
        Files
        =====

        :e <path>         - open in a new tab (a directory: its tree)
        :open             - open here (dialog)
        Ctrl+P            - pick a file with :set picker=<command>
        Ctrl+S / :w       - save;  :w <path> / Ctrl+Shift+S - save as
        :wa               - save every graph and every text file
        F5 / :e           - reload from the disk
        :e!               - reload, unsaved text files too
        :file             - which file this is
        :browse [dir]     - file tree; gf on a node opens it
        :tabnew           - file tree of the workspace
        :cd <dir>         - switch the workspace (its own tabs)

        Ctrl+E / :export svg <path> - export what is visible
        Ctrl+Shift+E / :export-selection svg <path>
                                    - export the selection
        dot in place of svg         - the same as DOT
        :q  :q!  :qa  :qa!  :wq     - quit as in vim
    """.trimIndent()

    val links = """
        Links
        =====

        A node attribute link="..." (or link-<letter>="...").

        gf        - follow link, in this tab
        gF        - follow it into a new tab
        g<letter> - follow link-<letter>
        Ctrl+O / Ctrl+I - jump back / forward

        A link is
          graph.dot            a graph
          graph.dot:<nodeid>   a node of it;  :<nodeid> here
          dir/                 a file tree
          file.txt             a text file, opened for editing
          file.kt:10-20        those lines of it
          README.md#Heading    a section of a markdown file
          sge:help             this help
          sge:examples         the examples

        A file name must not contain : or #.
    """.trimIndent()

    val tabs = """
        Tabs & clipboard
        ================

        Ctrl+T / Ctrl+N       - open a file in a new tab
        Ctrl+W                - close the tab
        gt / Ctrl+Tab / Ctrl+PgDn       - next tab
        gT / Ctrl+Shift+Tab / Ctrl+PgUp - previous tab

        Ctrl+C / Ctrl+X - copy / cut the selected nodes
        Ctrl+V          - paste at the pointer (also in another tab)
    """.trimIndent()

    val commandLine = """
        Command line & settings
        =======================

        :           - command line;  / and ? search
        Tab         - complete (commands, settings, paths)
        Shift+Tab   - back;  Ctrl+D - list the completions
        Up / Down   - history

        :set <mode> / :set no<mode> / :set <mode>!
          unhide, show-parents, show-children, dynamic,
          discard (never ask about unsaved changes), 3d
        :set <name>=<value>
          timeoutlen, spring-scale, spring-scale-step, picker

        :source <file> - run the commands of a file
        The init file is run when the application starts
        (:help init tells where it is).
    """.trimIndent()

    val macros = """
        Macros & mappings
        =================

        q<letter> - record keys into a register; q ends it
        @<letter> - replay it

        :map <keys> <keys>   - map keys to keys, read with their
                               built-in meanings (also :noremap)
        :remap <keys> <keys> - read with other mappings too
        :unmap <keys>
        :map                   - list what is bound

        e.g.  :map gh tih0<Esc>
        A mapped g<letter> comes before link-<letter>.
        A command in a mapping: :command<CR>
    """.trimIndent()

    val sessions = """
        Sessions & scripting
        ====================

        The open tabs are remembered per directory.
        Unsaved graphs are backed up every 5 minutes to
        ~/.cache/sane-graph-edit/backups (never restored
        or deleted by the application).

        sane-graph-edit --attach - join the session of the window
                                   running in this directory:
                                   one document, shared undo
        sane-graph-edit ctl help - read and change the documents
                                   of a running window from a script
        sane-graph-edit --ws <port> - the same over a WebSocket
                                   (this machine only, with a token)
        sane-graph-edit <file> -e '<keys>' - run the keys without
                                   a window and exit (-u: stay open)
    """.trimIndent()
}
