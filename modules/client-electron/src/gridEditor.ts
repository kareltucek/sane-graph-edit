import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import type { ChildProcess } from "child_process";
import type { NeovimClient } from "neovim";
import type { Colours } from "./nodeEditor";

// The text editor of a node: neovim without a terminal. Neovim
// tells what is in every cell of its screen (its UI protocol) and
// the page draws the cells itself, with the font and at the places
// the node's text is drawn with: nothing moves when editing starts,
// and nothing is stretched.
//
// Neovim with a user's plugins may take seconds to start, so one
// is always kept started and waiting; an edit takes that one and
// gives it the text.

/** Where the cells go on the canvas, in page pixels, and the font they are drawn in. */
/** What is drawn on: the page's canvas, or one that is kept as a bitmap. */
export type Canvas2D = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;

export interface Cells {
    /** The corner of the cell in row 0, column 0. */
    left: number;
    top: number;
    cellWidth: number;
    cellHeight: number;
    /** A CSS font, e.g. `12px monospace`, and how far below a cell's top its baseline is. */
    font: string;
    baseline: number;
    /** What may be drawn on: the inside of the node. */
    clip: { left: number; top: number; width: number; height: number };
}

/** How neovim wants a kind of text drawn; colours are `#rrggbb`, and absent means the default one. */
interface Highlight {
    foreground?: string;
    background?: string;
    special?: string;
    reverse?: boolean;
    bold?: boolean;
    italic?: boolean;
    underline?: boolean;
    undercurl?: boolean;
    strikethrough?: boolean;
}

interface Cell {
    text: string;
    highlight: number;
}

/** A cell as it is drawn: its text, and what its highlight comes to. */
export type PaintedCell = { text: string } & Colours & Highlight;

/** Text as neovim shows it, cell by cell: for drawing it without a neovim. [background] is the one cells have unless they say another. */
export interface Painting {
    rows: PaintedCell[][];
    background: string;
}

/** What the cursor looks like in a mode: a block, a bar at the cell's left, or a line at its bottom, the last two this many percent of the cell. */
interface CursorStyle {
    shape: "block" | "vertical" | "horizontal";
    percentage: number;
}

/**
 * Room beyond the text, so that neovim need not scroll before its
 * screen has grown: the cursor behind the last character plus the
 * characters typed before the node has grown, and the line a new
 * line opens.
 */
const SPARE_COLUMNS = 4;
const SPARE_ROWS = 1;

/** Neovim draws at least this much whatever size it is given. */
const MIN_COLUMNS = 12;
const MIN_ROWS = 3;

const NVIM = process.env.SGE_NVIM || "nvim";

/** How often and for how long to ask a starting neovim whether it is through with the user's init files. */
const START_RETRY_MS = 50;
const START_TRIES = 200;

/** How long closing waits for neovim to tell its text. */
const CLOSE_TIMEOUT_MS = 3000;

/** A painting is taken at neovim's next redraw, or after this long if it sees nothing to redraw. */
const PAINT_TIMEOUT_MS = 300;

/** How long a neovim that was asked to end has for it. */
const QUIT_TIMEOUT_MS = 1000;

/** In an editor of all of a file, how far toward the text's colour the background of the node's lines is. */
const MARK_SHARE = 0.12;

/** The colour [share] of the way from [from] to [to], both `#rrggbb`; [from] when either is not one. */
function mix(from: string, to: string, share: number): string {
    const rgb = (c: string) => (/^#[0-9a-fA-F]{6}$/.test(c) ? [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16)) : null);
    const a = rgb(from);
    const b = rgb(to);
    return a === null || b === null ? from : "#" + a.map((v, i) => Math.round(v + (b[i] - v) * share).toString(16).padStart(2, "0")).join("");
}

/** The editor shows once neovim has drawn the text, or after this long if it draws nothing. */
const REVEAL_TIMEOUT_MS = 300;

// In the node there must be the text and nothing else: no status line, no columns beside the text. The command line and the messages are not on the screen at all:
// neovim tells them and the page shows them where its own are.
// (cmdheight=0 all the same: without it the last row of the screen is kept for a command line that is never drawn there.)
const BARE = "set laststatus=0 showtabline=0 cmdheight=0 noruler signcolumn=no nonumber norelativenumber foldcolumn=0 nofoldenable nowrap noswapfile sidescrolloff=0 shortmess+=IF";

/** A stretch of neovim's status line in one colouring; colours are `#rrggbb`, and absent means the page's own. */
export interface StatusPiece {
    text: string;
    foreground?: string;
    background?: string;
}

/** The status line is asked for this long after the screen changed, once for all changes in between. */
const STATUS_DELAY_MS = 60;

// The user's status line (or neovim's usual one when none is set), evaluated for a width of `...` characters, as a list of {text, foreground, background}.
const STATUS_LINE = `
local width = ...
local line = vim.o.statusline
if line == '' then line = '%<%f %h%w%m%r%=%-14.(%l,%c%V%) %P' end
local ok, evaluated = pcall(vim.api.nvim_eval_statusline, line, { maxwidth = width, highlights = true })
if not ok then return {} end
local colour = function(value) return value and string.format('#%06x', value) or nil end
local pieces = {}
local marks = evaluated.highlights or {}
for i, mark in ipairs(marks) do
  local to = marks[i + 1] and marks[i + 1].start or #evaluated.str
  local text = evaluated.str:sub(mark.start + 1, to)
  if text ~= '' then
    local hl = vim.api.nvim_get_hl(0, { name = mark.group, link = false })
    local fg, bg = hl.fg, hl.bg
    if hl.reverse then fg, bg = bg, fg end
    table.insert(pieces, { text = text, foreground = colour(fg), background = colour(bg) })
  end
end
if #marks == 0 then table.insert(pieces, { text = evaluated.str }) end
return pieces
`;

const hex = (colour: number) => "#" + colour.toString(16).padStart(6, "0");

/** Keys as a DOM event names them, as neovim's `nvim_input` wants them. */
const SPECIAL_KEYS: { [key: string]: string } = {
    Escape: "Esc", Enter: "CR", Backspace: "BS", Tab: "Tab", Delete: "Del", Insert: "Insert", " ": "Space", "<": "lt", "\\": "Bslash", "|": "Bar",
    ArrowUp: "Up", ArrowDown: "Down", ArrowLeft: "Left", ArrowRight: "Right", Home: "Home", End: "End", PageUp: "PageUp", PageDown: "PageDown",
    F1: "F1", F2: "F2", F3: "F3", F4: "F4", F5: "F5", F6: "F6", F7: "F7", F8: "F8", F9: "F9", F10: "F10", F11: "F11", F12: "F12",
};

/** A key press in neovim's notation; null for a key that is nothing by itself (a modifier, a dead key). */
export function keyNotation(e: { key: string; ctrlKey: boolean; altKey: boolean; shiftKey: boolean; metaKey: boolean }): string | null {
    const special = SPECIAL_KEYS[e.key];
    const printable = e.key.length === 1 || [...e.key].length === 1;
    const name = special ?? (printable ? e.key : null);
    // a shifted character is that character already; Shift is only said for keys with a name
    const modifiers = (e.ctrlKey ? "C-" : "") + (e.altKey ? "M-" : "") + (e.metaKey ? "D-" : "") + (e.shiftKey && special !== undefined && e.key !== "<" && e.key !== "|" ? "S-" : "");
    return name === null ? null : modifiers !== "" || special !== undefined ? `<${modifiers}${name}>` : name;
}

export class GridEditor {
    /** Started and waiting for the next edit. */
    private static spare: GridEditor | null = null;

    private readonly process: ChildProcess;
    private readonly directory = fs.mkdtempSync(path.join(os.tmpdir(), "sge-node-"));
    private readonly nvim: Promise<NeovimClient>;
    private lines: string[] = [""];
    /** Typed before neovim had the text. */
    private typed: string[] = [];
    private loaded = false;
    private exited = false;
    private closed = false;
    private onText: (text: string) => void = () => {};
    /** In an editor of all of a file ([openFile]): lines [first] up to [last] (from 0, without [last]) became [replacement]. */
    private onLines: ((first: number, last: number, replacement: string[]) => void) | null = null;
    /** `:w` in an editor of all of a file: the page saves, neovim writes nothing itself. */
    private onWrite: () => void = () => {};
    private onExit: () => void = () => {};
    private onShown: () => void = () => {};
    private onDrawn: () => void = () => {};
    private onLine: (text: string | null) => void = () => {};
    private onMessage: (text: string) => void = () => {};
    private onStatus: (pieces: StatusPiece[]) => void = () => {};

    // --- neovim's screen as it has told it ---
    private columns = MIN_COLUMNS;
    private rows = MIN_ROWS;
    private cells: Cell[][] = [];
    private highlights = new Map<number, Highlight>();
    private defaults: Colours = { foreground: "#ffffff", background: "#000000" };
    private cursor = { row: 0, column: 0 };
    private cursorStyles: CursorStyle[] = [];
    private cursorStyle: CursorStyle = { shape: "block", percentage: 100 };
    /** The command line being typed (`:`, `/`), and the mode as neovim words it (`-- INSERT --`); the first shows if there is one. */
    private commandLine: string | null = null;
    private modeText = "";
    /** The mode neovim is in, as it names it to a UI: `normal`, `insert`, `visual`, `operator`, `cmdline_normal`, … */
    private mode = "normal";

    /** True once the editor is to be seen: until then the node's text is still the renderer's to draw. */
    shown = false;

    /** Have a neovim started for the next edit. */
    static warm() {
        if (GridEditor.spare === null || GridEditor.spare.exited) GridEditor.spare = new GridEditor();
    }

    /**
     * Neovim on [text] with the cursor at [row], [column] (from 0), plain text in [colours]; in insert mode with [insert].
     * [onText] is told the text whenever it changes; [onExit] when neovim ends by itself (`:q`);
     * [onShown] when the editor takes the text's place on the screen; [onDrawn] whenever what it shows changed;
     * [onLine] the one line to show below the graph while editing (the command line being typed, or the mode), or null for none;
     * [onMessage] what neovim has to say (an error, the output of a command);
     * [onStatus] neovim's status line, in pieces with their colours.
     */
    static open(
        text: string,
        row: number,
        column: number,
        colours: Colours,
        filetype: string,
        insert: boolean,
        on: {
            text: (text: string) => void;
            exit: () => void;
            shown: () => void;
            drawn: () => void;
            line: (text: string | null) => void;
            message: (text: string) => void;
            status: (pieces: StatusPiece[]) => void;
        },
    ): GridEditor {
        GridEditor.warm();
        const editor = GridEditor.spare!;
        GridEditor.spare = null;
        editor.onText = on.text;
        editor.onExit = on.exit;
        editor.onShown = on.shown;
        editor.onDrawn = on.drawn;
        editor.onLine = on.line;
        editor.onMessage = on.message;
        editor.onStatus = on.status;
        editor.load(text.split("\n"), row, column, 0, colours, filetype, insert, () => Promise.resolve());
        GridEditor.warm();
        return editor;
    }

    /**
     * Neovim on all of the file [file] a node keeps its text in, as
     * the page has it (unsaved edits included): its buffer is named
     * as the file, the node's lines ([file] `first` up to `last`, from
     * 0) are marked and seen first, the cursor at [row], [column] of
     * them. What changes is told by line ([on] `lines`), not as text;
     * `:w` is told ([on] `write`) and writes nothing. Otherwise as
     * [open].
     */
    static openFile(
        file: { path: string; lines: string[]; first: number; last: number },
        row: number,
        column: number,
        colours: Colours,
        filetype: string,
        insert: boolean,
        on: {
            lines: (first: number, last: number, replacement: string[]) => void;
            write: () => void;
            exit: () => void;
            shown: () => void;
            drawn: () => void;
            line: (text: string | null) => void;
            message: (text: string) => void;
            status: (pieces: StatusPiece[]) => void;
        },
    ): GridEditor {
        GridEditor.warm();
        const editor = GridEditor.spare!;
        GridEditor.spare = null;
        editor.onLines = on.lines;
        editor.onWrite = on.write;
        editor.onExit = on.exit;
        editor.onShown = on.shown;
        editor.onDrawn = on.drawn;
        editor.onLine = on.line;
        editor.onMessage = on.message;
        editor.onStatus = on.status;
        const lines = file.lines.length > 0 ? file.lines : [""];
        const setUp = (nvim: NeovimClient) =>
            nvim.request("nvim_buf_set_name", [0, file.path])
                .then(() => nvim.request("nvim_get_api_info", []))
                // a write is the page's; what neovim would write is told instead, to this page only (plugins listen on channel 0)
                .then(([channel]: [number]) =>
                    nvim.command(`set nomodified | autocmd BufWriteCmd <buffer> call rpcnotify(${channel}, 'sge_write') | set nomodified`))
                // the page writes the file behind neovim's back: that is no news to ask about or to read again
                .then(() => nvim.command("setlocal noautoread | autocmd FileChangedShell <buffer> let v:fcs_choice = ''"))
                // the node's lines on a background a little toward the text's colour; what is put in at their edges is theirs
                .then(() => nvim.command(`highlight SgeNode guibg=${mix(colours.background, colours.foreground, MARK_SHARE)}`))
                .then(() => nvim.request("nvim_create_namespace", ["sge"]))
                .then((space: number) => nvim.request("nvim_buf_set_extmark", [0, space, file.first, 0, {
                    end_row: Math.max(file.first, file.last), hl_group: "SgeNode", hl_eol: true, priority: 1, right_gravity: false, end_right_gravity: true,
                }]));
        editor.load(lines, file.first + row, column, file.first, colours, filetype, insert, setUp);
        GridEditor.warm();
        return editor;
    }

    // --- text as neovim shows it, for nodes that are not being edited ---

    /** The neovim that paints, one for the page; and what it is to paint, one after the other. */
    private static painter: GridEditor | null = null;
    private static painted: Promise<unknown> = Promise.resolve();

    /**
     * [text] as neovim shows a file of type [filetype] in [colours]:
     * its syntax highlighting, the user's own. For drawing with
     * [drawPainting].
     */
    static paint(text: string, filetype: string, colours: Colours): Promise<Painting> {
        const job = GridEditor.painted.then(() => {
            if (GridEditor.painter === null || GridEditor.painter.exited) GridEditor.painter = new GridEditor();
            return GridEditor.painter.paintText(text, filetype, colours);
        });
        // one that fails does not stop those after it
        GridEditor.painted = job.catch(() => {});
        return job;
    }

    /** How many `flush` events neovim has sent: the screen is as neovim means it at each. */
    private flushes = 0;
    private onFlush: () => void = () => {};

    private paintText(text: string, filetype: string, colours: Colours): Promise<Painting> {
        const lines = text.split("\n");
        const columns = Math.max(Math.max(1, ...lines.map((l) => [...l].length)), MIN_COLUMNS);
        const rows = Math.max(lines.length, MIN_ROWS);
        return this.nvim.then((nvim) => {
            let before = 0;
            return nvim.uiTryResize(columns, rows)
                .then(() => nvim.request("nvim_buf_set_lines", [0, 0, -1, false, lines]))
                .then(() => {
                    before = this.flushes;
                    // nothing of an editor in the picture: no line or column under a cursor, the text from its first character
                    return nvim.command(
                        `setlocal filetype=${filetype === "" ? "text" : filetype} nocursorline nocursorcolumn | call cursor(1, 1)` +
                        ` | call winrestview({'topline': 1, 'leftcol': 0})` +
                        ` | highlight! Normal guifg=${colours.foreground} guibg=${colours.background} | redraw!`,
                    );
                })
                // answers arrive in order with the screen's events: a flush after the command was sent has been counted by now, if there was one
                .then(() => (this.flushes > before ? Promise.resolve() : this.nextFlush()))
                .then(() => {
                    const all = this.painting();
                    return { rows: all.rows.slice(0, lines.length), background: all.background };
                });
        });
    }

    private nextFlush(): Promise<void> {
        return new Promise((resolve) => {
            const done = () => {
                this.onFlush = () => {};
                resolve();
            };
            this.onFlush = done;
            setTimeout(done, PAINT_TIMEOUT_MS);
        });
    }

    /** The colours of the waiting neovim's colour scheme; null if it has none that a page could use. */
    static colours(): Promise<Colours | null> {
        const nvim = GridEditor.spare?.nvim ?? Promise.reject(new Error("no neovim"));
        return nvim
            .then((it) => it.request("nvim_get_hl", [0, { name: "Normal" }]))
            .then((normal: { fg?: number; bg?: number }) =>
                normal.fg !== undefined && normal.bg !== undefined ? { foreground: hex(normal.fg), background: hex(normal.bg) } : null)
            .catch(() => null);
    }

    /** The window goes away: so does the waiting neovim. */
    static discardSpare() {
        GridEditor.spare?.discard(true);
        GridEditor.spare = null;
    }

    /** Every editor whose neovim still runs: the waiting one, the one in use, and those that are closing. */
    private static readonly running = new Set<GridEditor>();

    /** The window goes away: every neovim of this page ends now. */
    static endAll() {
        GridEditor.spare = null;
        GridEditor.running.forEach((editor) => editor.discard(true));
    }

    private constructor() {
        const file = path.join(this.directory, "node.txt");
        fs.writeFileSync(file, "");
        this.clear();

        // loaded here, not bundled: neovim's client expects to be a Node module
        const { spawn }: typeof import("child_process") = require("child_process");
        const { attach }: typeof import("neovim") = require("neovim");
        // embedded: its screen is what it tells over its standard streams, and it starts once somebody is there to be told
        // a process group of its own: neovim may be several processes (packed as an AppImage it is), and they are ended together
        this.process = spawn(NVIM, ["--embed", "-c", BARE, file], { cwd: this.directory, env: process.env, detached: true });
        GridEditor.running.add(this);
        this.process.on("exit", () => {
            this.exited = true;
            GridEditor.running.delete(this);
            if (!this.closed) this.onExit();
        });
        this.process.on("error", () => {
            this.exited = true;
        });
        const client = attach({ proc: this.process });
        client.on("notification", (method: string, events: unknown[][]) => {
            if (method === "redraw") this.redraw(events);
            else if (method === "sge_write" && !this.closed) this.onWrite();
        });
        this.nvim = client
            .uiAttach(this.columns, this.rows, { rgb: true, ext_linegrid: true, ext_cmdline: true, ext_messages: true } as never)
            .then(() => this.started(client, START_TRIES));
        // a neovim that never comes is found out when it is used
        this.nvim.catch(() => {});
    }

    /** The text as far as neovim has told. */
    get text(): string {
        return this.lines.join("\n");
    }

    /** How many columns and rows there is room for on the screen; null while that is not known. */
    private room: { columns: number; rows: number } | null = null;

    /**
     * The editor is never bigger than what is seen of its node: there
     * is room for [columns] by [rows]. Text beyond that is reached by
     * neovim's own scrolling.
     */
    limit(columns: number, rows: number) {
        if (this.room?.columns !== columns || this.room?.rows !== rows) {
            this.room = { columns, rows };
            if (!this.closed) this.fit();
        }
    }

    private get textColumns(): number {
        return Math.max(Math.max(1, ...this.lines.map((l) => [...l].length)) + SPARE_COLUMNS, MIN_COLUMNS);
    }

    private get textRows(): number {
        return Math.max(this.lines.length + SPARE_ROWS, MIN_ROWS);
    }

    private get wantedColumns(): number {
        return Math.max(Math.min(this.textColumns, this.room?.columns ?? Infinity), MIN_COLUMNS);
    }

    private get wantedRows(): number {
        return Math.max(Math.min(this.textRows, this.room?.rows ?? Infinity), MIN_ROWS);
    }

    /** Neovim answers while it still reads the user's init files; it is of use once it is through with them. */
    private started(nvim: NeovimClient, triesLeft: number): Promise<NeovimClient> {
        return nvim.eval("v:vim_did_enter").then((entered) =>
            entered === 1 || triesLeft === 0
                ? nvim
                : new Promise((resolve) => setTimeout(resolve, START_RETRY_MS)).then(() => this.started(nvim, triesLeft - 1)));
    }

    /**
     * Give the waiting neovim its [text], as if it had been started on it: nothing to undo, the cursor in place, line
     * [topline] (from 0) the first one seen; [setUp] does what else there is to do once it has the text. One that fails
     * leaves the text as it is.
     */
    private load(
        text: string[], row: number, column: number, topline: number, colours: Colours, filetype: string, insert: boolean,
        setUp: (nvim: NeovimClient) => Promise<unknown>,
    ) {
        // the kind of text decides neovim's highlighting, indenting and the rest of what it does by file type
        const kind = filetype === "" ? "" : ` | setlocal filetype=${filetype}`;
        const mode = insert ? " | startinsert" : "";
        this.lines = text;
        const lines = [...this.lines];
        this.nvim
            .then((nvim) =>
                nvim.command("let g:sge_undolevels = &undolevels | set undolevels=-1")
                    .then(() => nvim.uiTryResize(this.wantedColumns, this.wantedRows))
                    .then(() => nvim.request("nvim_buf_set_lines", [0, 0, -1, false, lines]))
                    // by character, not by byte: the column is one of the text as drawn
                    .then(() => nvim.command(
                        `let &undolevels = g:sge_undolevels | set nomodified | call setcursorcharpos(${row + 1}, ${column + 1})` +
                        ` | call winrestview({'topline': ${topline + 1}, 'leftcol': 0})${kind}` +
                        ` | highlight! Normal guifg=${colours.foreground} guibg=${colours.background}${mode}`,
                    ))
                    .then(() => setUp(nvim).catch(() => {})))
            .catch(() => {
                // no neovim: the editor shows nothing, and closes with the text unchanged
            })
            .then(() => {
                if (!this.closed) {
                    this.loaded = true;
                    this.typed.forEach((keys) => this.send(keys));
                    this.typed = [];
                    this.follow();
                    setTimeout(() => this.reveal(), REVEAL_TIMEOUT_MS);
                }
            });
    }

    private reveal() {
        if (!this.shown && !this.closed) {
            this.shown = true;
            this.onShown();
        }
    }

    /** Take over the buffer's changes as they happen. */
    private follow() {
        this.nvim.then((nvim) => nvim.buffer).then((buffer) => {
            buffer.listen("lines", (_buffer: unknown, _tick: number, first: number, last: number, replacement: string[]) => {
                this.lines.splice(first, last - first, ...replacement);
                if (this.lines.length === 0) this.lines = [""];
                // also while closing: neovim tells a change before it answers the last asking for the lines
                this.onLines?.(first, last, replacement);
                this.changed();
            });
            // what was typed before this point caused no event
            return buffer.lines;
        }).then((lines) => {
            this.caughtUp(lines);
            this.changed();
        }).catch(() => {
            // neovim went away meanwhile; the text stays as it was last told
        });
    }

    /**
     * Neovim's buffer holds [lines]: what it has that no event told (typed before the events were listened to, or
     * not told yet when the editor closes) is told as the lines between what stayed at the start and at the end.
     */
    private caughtUp(lines: string[]) {
        const now = lines.length > 0 ? lines : [""];
        const was = this.lines;
        let start = 0;
        while (start < was.length && start < now.length && was[start] === now[start]) start++;
        let end = 0;
        while (end < was.length - start && end < now.length - start && was[was.length - 1 - end] === now[now.length - 1 - end]) end++;
        this.lines = now;
        if (start !== was.length || start !== now.length) this.onLines?.(start, was.length - end, now.slice(start, now.length - end));
    }

    private changed() {
        if (!this.closed) {
            this.fit();
            this.onText(this.text);
        }
    }

    /** As many rows and columns as the text has now, or as few as neovim can do with. */
    private fit() {
        if (this.columns !== this.wantedColumns || this.rows !== this.wantedRows) {
            // all of the text is on the screen, from its first cell, unless there is no room for all of it: then neovim scrolls
            const whole = this.wantedColumns === this.textColumns && this.wantedRows === this.textRows;
            this.nvim
                .then((nvim) => nvim.uiTryResize(this.wantedColumns, this.wantedRows).then(() => (whole ? nvim.call("winrestview", [{ topline: 1, leftcol: 0 }]) : undefined)))
                .catch(() => {});
        }
    }

    // --- what neovim says is on its screen ---

    private clear() {
        this.cells = Array.from({ length: this.rows }, () => Array.from({ length: this.columns }, () => ({ text: " ", highlight: 0 })));
    }

    /** One `redraw` notification: batches of events of one kind, `[name, arguments, arguments, …]`. */
    private redraw(batches: unknown[][]) {
        let flushed = false;
        for (const [name, ...calls] of batches as [string, ...unknown[][]][]) {
            for (const a of calls as any[][]) {
                if (name === "grid_resize") {
                    this.columns = a[1];
                    this.rows = a[2];
                    this.clear();
                } else if (name === "grid_clear") {
                    this.clear();
                } else if (name === "default_colors_set") {
                    // -1: neovim has none of its own
                    if (a[0] >= 0) this.defaults.foreground = hex(a[0]);
                    if (a[1] >= 0) this.defaults.background = hex(a[1]);
                } else if (name === "hl_attr_define") {
                    const attributes = a[1] as { [key: string]: number | boolean };
                    const colour = (key: string) => (typeof attributes[key] === "number" ? hex(attributes[key] as number) : undefined);
                    this.highlights.set(a[0], { ...attributes, foreground: colour("foreground"), background: colour("background"), special: colour("special") } as Highlight);
                } else if (name === "grid_line") {
                    this.line(a[1], a[2], a[3]);
                } else if (name === "grid_scroll") {
                    this.scroll(a[1], a[2], a[3], a[4], a[5]);
                } else if (name === "grid_cursor_goto") {
                    this.cursor = { row: a[1], column: a[2] };
                } else if (name === "mode_info_set") {
                    this.cursorStyles = (a[1] as { cursor_shape?: CursorStyle["shape"]; cell_percentage?: number }[])
                        .map((mode) => ({ shape: mode.cursor_shape ?? "block", percentage: mode.cell_percentage || 100 }));
                } else if (name === "mode_change") {
                    this.mode = a[0] as string;
                    this.cursorStyle = this.cursorStyles[a[1]] ?? { shape: "block", percentage: 100 };
                } else if (name === "cmdline_show") {
                    // content in pieces, the character it began with (`:`), a prompt, the indent
                    this.commandLine = (a[2] as string) + (a[3] as string) + " ".repeat(a[4] as number) + (a[0] as [unknown, string][]).map((piece) => piece[1]).join("");
                    this.tellLine();
                } else if (name === "cmdline_hide") {
                    this.commandLine = null;
                    this.tellLine();
                } else if (name === "msg_showmode") {
                    this.modeText = (a[0] as [unknown, string][]).map((piece) => piece[1]).join("");
                    this.tellLine();
                } else if (name === "msg_show") {
                    const text = (a[1] as [unknown, string][]).map((piece) => piece[1]).join("");
                    // what was only typed back at the user (a search hit bottom, a file written) is not worth a pane
                    if (text.trim() !== "" && a[0] !== "search_count" && a[0] !== "") this.onMessage(text);
                } else if (name === "flush") {
                    flushed = true;
                }
            }
        }
        if (flushed) {
            this.flushes++;
            this.onFlush();
        }
        if (flushed && !this.closed) {
            if (this.loaded) this.reveal();
            this.onDrawn();
            this.tellStatus();
        }
    }

    private tellLine() {
        const text = this.commandLine ?? this.modeText;
        if (!this.closed) this.onLine(text === "" ? null : text);
    }

    /** How many characters the page's strip for the status line holds; the page says. */
    statusColumns = 120;

    private statusAsked = false;

    /**
     * Neovim's status line is not on its screen here (the screen is
     * the node's text): it is asked for, in pieces with their
     * colours, soon after the screen changed.
     */
    private tellStatus() {
        if (!this.statusAsked && this.loaded && !this.closed) {
            this.statusAsked = true;
            setTimeout(() => {
                this.statusAsked = false;
                this.nvim
                    .then((nvim) => nvim.lua(STATUS_LINE, [this.statusColumns]))
                    .then((pieces) => {
                        if (!this.closed) this.onStatus(pieces as StatusPiece[]);
                    })
                    .catch(() => {});
            }, STATUS_DELAY_MS);
        }
    }

    /** Cells of [row] from [column] on: `[text, highlight?, repeat?]`; a highlight stays until the next one is named. */
    private line(row: number, column: number, cells: [string, number?, number?][]) {
        const target = this.cells[row];
        let at = column;
        let highlight = 0;
        for (const [text, named, repeat] of cells) {
            if (named !== undefined) highlight = named;
            for (let i = 0; i < (repeat ?? 1) && target !== undefined && at < target.length; i++) target[at++] = { text, highlight };
        }
    }

    /** Rows [top], [bottom]) columns [left], [right]) move up by [rows] (down when negative); what they uncover is drawn afterwards. */
    private scroll(top: number, bottom: number, left: number, right: number, rows: number) {
        // each row takes what was [rows] below it, in the order that reads a row before it is overwritten
        const targets = rows > 0 ? range(top, bottom - rows) : range(top - rows, bottom).reverse();
        for (const row of targets) {
            for (let column = left; column < right; column++) this.cells[row][column] = this.cells[row + rows][column];
        }
    }

    // --- drawing: the page calls this after it has drawn the graph ---

    /** Draw neovim's screen at [at]: the rows and columns of the text and what the node has room for beyond them. */
    draw(ctx: CanvasRenderingContext2D, at: Cells) {
        GridEditor.drawPainting(ctx, at, this.painting(), () => this.drawCursor(ctx, at));
    }

    /** The screen as it is now, every cell with what its highlight comes to. */
    private painting(): Painting {
        return { rows: this.cells.map((cells) => cells.map((cell) => ({ text: cell.text, ...this.style(cell.highlight) }))), background: this.defaults.background };
    }

    /**
     * Draw [painting] at [at], inside its clip; [over] draws on top of it within the same clip (the cursor).
     * Of a painting only partly [seen] (from where to where, top to bottom), the rows that are.
     */
    static drawPainting(ctx: Canvas2D, at: Cells, painting: Painting, over: () => void = () => {}, seen?: { top: number; bottom: number }) {
        ctx.save();
        ctx.beginPath();
        ctx.rect(at.clip.left, at.clip.top, at.clip.width, at.clip.height);
        ctx.clip();
        ctx.textBaseline = "alphabetic";
        // a row more on each side: what reaches out of its cell
        const first = seen === undefined ? 0 : Math.max(0, Math.floor((seen.top - at.top) / at.cellHeight) - 1);
        const end = seen === undefined ? painting.rows.length : Math.ceil((seen.bottom - at.top) / at.cellHeight) + 1;
        painting.rows.slice(first, Math.max(first, end)).forEach((cells, index) => {
            const row = first + index;
            const top = at.top + row * at.cellHeight;
            cells.forEach((cell, column) => {
                // a little more than the cell: no hairline between two cells of one colour
                if (cell.background !== painting.background) {
                    ctx.fillStyle = cell.background;
                    ctx.fillRect(at.left + column * at.cellWidth, top, at.cellWidth + 0.5, at.cellHeight + 0.5);
                }
            });
            GridEditor.runsOf(cells).forEach((run) => GridEditor.drawText(ctx, at, run.cell, at.left + run.column * at.cellWidth, top, run.length));
        });
        over();
        ctx.restore();
    }

    /** The colours a highlight comes to, the default ones where it names none, exchanged where it says so. */
    private style(id: number): Colours & Highlight {
        const h = this.highlights.get(id) ?? {};
        const foreground = h.foreground ?? this.defaults.foreground;
        const background = h.background ?? this.defaults.background;
        return h.reverse ? { ...h, foreground: background, background: foreground } : { ...h, foreground, background };
    }

    /**
     * The cells of a row as runs that are drawn at once: neighbours
     * that look the same, each a single character of the plain
     * ones, which are all one cell wide. Anything else is a run of
     * its own, drawn in its own cell.
     */
    private static runsOf(cells: PaintedCell[]): { cell: PaintedCell; column: number; length: number }[] {
        const plain = (cell: PaintedCell) => /^[\x20-\x7e]+$/.test(cell.text);
        const alike = (a: PaintedCell, b: PaintedCell) =>
            a.foreground === b.foreground && a.special === b.special && !a.bold === !b.bold && !a.italic === !b.italic &&
            !a.underline === !b.underline && !a.undercurl === !b.undercurl && !a.strikethrough === !b.strikethrough;
        return cells.reduce<{ cell: PaintedCell; column: number; length: number }[]>((runs, cell, column) => {
            const last = runs[runs.length - 1];
            if (last !== undefined && plain(cell) && plain(last.cell) && alike(cell, last.cell)) {
                last.cell = { ...last.cell, text: last.cell.text + cell.text };
                last.length += 1;
            } else runs.push({ cell, column, length: 1 });
            return runs;
        }, []);
    }

    /** The text of [cell] from [left], and what is drawn under or through it, [length] cells long. */
    private static drawText(ctx: Canvas2D, at: Cells, cell: PaintedCell, left: number, top: number, length: number = 1) {
        if (cell.text.trim() !== "") {
            ctx.font = `${cell.italic ? "italic " : ""}${cell.bold ? "bold " : ""}${at.font}`;
            ctx.fillStyle = cell.foreground;
            ctx.fillText(cell.text, left, top + at.baseline);
        }
        if (cell.underline || cell.undercurl || cell.strikethrough) {
            ctx.fillStyle = cell.special ?? cell.foreground;
            const thickness = Math.max(1, at.cellHeight / 16);
            ctx.fillRect(left, top + (cell.strikethrough ? at.cellHeight / 2 : at.baseline + thickness), at.cellWidth * length, thickness);
        }
    }

    private drawCursor(ctx: CanvasRenderingContext2D, at: Cells) {
        const cell = this.cells[this.cursor.row]?.[this.cursor.column];
        // while a command line is typed the cursor is there, not in the text
        if (cell !== undefined && this.commandLine === null) {
            const style = this.style(cell.highlight);
            const left = at.left + this.cursor.column * at.cellWidth;
            const top = at.top + this.cursor.row * at.cellHeight;
            const share = this.cursorStyle.percentage / 100;
            ctx.fillStyle = style.foreground;
            if (this.cursorStyle.shape === "vertical") ctx.fillRect(left, top, Math.max(1, at.cellWidth * share), at.cellHeight);
            else if (this.cursorStyle.shape === "horizontal") ctx.fillRect(left, top + at.cellHeight * (1 - share), at.cellWidth, at.cellHeight * share);
            else {
                ctx.fillRect(left, top, at.cellWidth, at.cellHeight);
                GridEditor.drawText(ctx, at, { text: cell.text, foreground: style.background, background: style.foreground }, left, top);
            }
        }
    }

    // --- input ---

    private send(keys: string) {
        this.nvim.then((nvim) => nvim.input(keys)).catch(() => {});
    }

    /** As if the user had typed [keys], in neovim's notation (`<Esc>`, `<C-w>`, a plain character). */
    type(keys: string) {
        if (this.loaded) this.send(keys);
        else this.typed.push(keys);
    }

    /** A key went down. True if it is neovim's now. */
    key(e: KeyboardEvent): boolean {
        const notation = keyNotation(e);
        // Escape in normal mode has nothing left to leave in neovim: it leaves the editor, as `:q` does
        const leaves = notation === "<Esc>" && this.mode === "normal" && this.commandLine === null;
        if (leaves) this.onExit();
        else if (notation !== null) this.type(notation);
        return notation !== null;
    }

    /** The wheel turned over the cell in [row], [column]: neovim scrolls the text. */
    wheel(direction: "up" | "down", row: number, column: number) {
        this.nvim.then((nvim) => nvim.inputMouse("wheel", direction, "", 0, row, column)).catch(() => {});
    }

    /** A mouse button went down, moved or came up over the cell in [row], [column]: `press`, `drag` or `release`. */
    mouse(action: "press" | "drag" | "release", row: number, column: number) {
        this.nvim.then((nvim) => nvim.inputMouse("left", action, "", 0, row, column)).catch(() => {});
    }

    /**
     * Close the editor and tell the text it held. Neovim is asked
     * once more: the user may have typed faster than the changes
     * were told. If it does not answer, the text as last told stands.
     */
    close(): Promise<string> {
        if (this.closed) return Promise.resolve(this.text);
        this.closed = true;
        this.onLine(null);
        // a neovim that had not got the text yet has nothing to tell
        const asked = !this.loaded ? Promise.resolve() : this.nvim.then((nvim) => nvim.buffer).then((buffer) => buffer.lines).then((lines) => {
            this.caughtUp(lines);
        });
        const waited = new Promise<void>((resolve) => setTimeout(resolve, CLOSE_TIMEOUT_MS));
        return Promise.race([asked, waited]).catch(() => {}).then(() => {
            this.discard();
            return this.text;
        });
    }

    /** End neovim. With [now] (the window is going away, nothing later will run) at once; otherwise it is asked first and given a moment. */
    private discard(now = false) {
        this.closed = true;
        const end = () => {
            try {
                // the whole group; SIGTERM lets an AppImage take its mount away
                if (!this.exited && this.process.pid !== undefined) process.kill(-this.process.pid, "SIGTERM");
            } catch {
                // gone already
            }
        };
        if (now) {
            end();
        } else {
            this.nvim.then((nvim) => nvim.quit()).catch(end);
            setTimeout(end, QUIT_TIMEOUT_MS);
        }
        fs.rmSync(this.directory, { recursive: true, force: true });
    }
}

/** The whole numbers from [from] up to but not including [to]. */
function range(from: number, to: number): number[] {
    return Array.from({ length: Math.max(0, to - from) }, (_, i) => from + i);
}
