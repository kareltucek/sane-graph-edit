import { Terminal } from "@xterm/xterm";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import type { IPty } from "node-pty";
import type { NeovimClient } from "neovim";

// The text editor of a node: neovim in a terminal laid over the
// node. The terminal has as many rows and columns as the text, and
// is stretched so that its cells are the cells the node's text is
// drawn in: what is where does not change when editing starts.
//
// Neovim with a user's plugins may take seconds to start, so one
// is always kept started and waiting; an edit takes that one and
// gives it the text.

/** Where the terminal goes, in page pixels: the corner of the text and the size of a character cell. */
export interface Place {
    left: number;
    top: number;
    cellWidth: number;
    cellHeight: number;
}

/** Neovim's own colours for plain text, `#rrggbb`. */
export interface Colours {
    foreground: string;
    background: string;
}

/** The terminal's own font size; it is stretched to the node's, so this only sets how sharp the letters are. */
export const TERMINAL_FONT_PX = 16;

/**
 * Room beyond the text, so that neovim need not scroll before the
 * terminal has grown: the cursor behind the last character plus
 * the character typed next, and the line a new line opens.
 */
const SPARE_COLUMNS = 2;
const SPARE_ROWS = 1;

/**
 * Of the editor, this many columns show on either side of the
 * text: an empty one of neovim's own on the left, and on the right
 * the first of the spare ones, where the cursor is when appending.
 */
export const PAD_COLUMNS = 1;

/** Neovim draws at least this much whatever size it is given. */
const MIN_COLUMNS = 12;
const MIN_ROWS = 3;

const NVIM = process.env.SGE_NVIM || "nvim";

/** How often and for how long to look for neovim's socket after starting it. */
const ATTACH_RETRY_MS = 50;
const ATTACH_TRIES = 200;

/** How often and for how long to ask neovim whether it has its terminal's new size. */
const SIZE_RETRY_MS = 5;
const SIZE_TRIES = 100;

/** How long closing waits for neovim to tell its text. */
const CLOSE_TIMEOUT_MS = 3000;

/** The editor shows once neovim has drawn the text, or after this long if it draws nothing. */
const REVEAL_TIMEOUT_MS = 300;

// Shown in the node there must be the text and nothing else: no status line, no command line,
// and beside the text only the fold column, as the empty column on the left.
const BARE = `set laststatus=0 showtabline=0 cmdheight=0 noruler noshowcmd noshowmode signcolumn=no nonumber norelativenumber foldcolumn=${PAD_COLUMNS} ` +
    "nofoldenable nowrap noswapfile sidescrolloff=0 shortmess+=IF | highlight! link FoldColumn Normal";

export class NodeEditor {
    /** Started and waiting for the next edit. */
    private static spare: NodeEditor | null = null;

    private readonly element = document.createElement("div");
    private readonly terminal: Terminal;
    private readonly process: IPty;
    private readonly directory = fs.mkdtempSync(path.join(os.tmpdir(), "sge-node-"));
    private readonly nvim: Promise<NeovimClient>;
    private lines: string[] = [""];
    /** Typed before neovim had the text. */
    private typed: string[] = [];
    private serverPid = 0;
    private loaded = false;
    private exited = false;
    private closed = false;
    private onText: (text: string) => void = () => {};
    private onExit: () => void = () => {};
    private onShown: () => void = () => {};

    /** True once the editor is to be seen: until then the node's text is still the renderer's to draw. */
    shown = false;

    /** Have a neovim started for the next edit. */
    static warm(host: HTMLElement, font: string) {
        if (NodeEditor.spare === null || NodeEditor.spare.exited) NodeEditor.spare = new NodeEditor(host, font);
    }

    /**
     * Neovim on [text] with the cursor at [row], [column] (from 0), plain text in [colours] if given and in its colour scheme's if not.
     * [onText] is told the text whenever it changes; [onExit] when neovim ends by itself (`:q`);
     * [onShown] when the editor takes the text's place on the screen.
     */
    static open(
        host: HTMLElement,
        font: string,
        text: string,
        row: number,
        column: number,
        colours: Colours | null,
        onText: (text: string) => void,
        onExit: () => void,
        onShown: () => void,
    ): NodeEditor {
        NodeEditor.warm(host, font);
        const editor = NodeEditor.spare!;
        NodeEditor.spare = null;
        editor.onText = onText;
        editor.onExit = onExit;
        editor.onShown = onShown;
        editor.load(text, row, column, colours);
        NodeEditor.warm(host, font);
        return editor;
    }

    /** The window goes away: so does the waiting neovim. */
    static discardSpare() {
        NodeEditor.spare?.discard();
        NodeEditor.spare = null;
    }

    private constructor(host: HTMLElement, font: string) {
        const file = path.join(this.directory, "node.txt");
        const socket = path.join(this.directory, "nvim.sock");
        fs.writeFileSync(file, "");

        // not to be seen yet, but there: the terminal measures itself, and takes the keys
        this.element.style.cssText = "position: fixed; transform-origin: 0 0; overflow: hidden; opacity: 0; pointer-events: none;";
        host.appendChild(this.element);
        this.terminal = new Terminal({ fontFamily: font, fontSize: TERMINAL_FONT_PX, cols: MIN_COLUMNS, rows: MIN_ROWS, scrollback: 0 });
        this.terminal.open(this.element);

        // loaded here, not bundled: node-pty is native code, and neovim's client expects to be a Node module
        const pty: typeof import("node-pty") = require("node-pty");
        this.process = pty.spawn(NVIM, ["--listen", socket, "-c", BARE, file], {
            name: "xterm-256color",
            cols: MIN_COLUMNS,
            rows: MIN_ROWS,
            cwd: this.directory,
            env: process.env as { [key: string]: string },
        });
        this.process.onData((data) => this.terminal.write(data, () => {
            if (this.loaded) this.reveal();
        }));
        this.terminal.onData((data) => this.type(data));
        this.process.onExit(() => {
            this.exited = true;
            if (!this.closed) this.onExit();
        });
        const attached = this.connect(socket, ATTACH_TRIES);
        attached.then((nvim) => nvim.call("getpid")).then((pid: number) => {
            this.serverPid = pid;
        }).catch(() => {});
        this.nvim = attached.then((nvim) => this.started(nvim, ATTACH_TRIES));
        // a neovim that never comes is found out when it is used
        this.nvim.catch(() => {});
    }

    /** The text as far as neovim has told. */
    get text(): string {
        return this.lines.join("\n");
    }

    private get textColumns(): number {
        return Math.max(1, ...this.lines.map((l) => l.length));
    }

    private get columns(): number {
        return PAD_COLUMNS + this.textColumns + SPARE_COLUMNS;
    }

    private get rows(): number {
        return Math.max(this.lines.length + SPARE_ROWS, MIN_ROWS);
    }

    /** Neovim's own protocol, once its socket is there; fails when it does not come. */
    private connect(socket: string, triesLeft: number): Promise<NeovimClient> {
        if (triesLeft === 0) return Promise.reject(new Error("neovim did not open its socket"));
        if (!fs.existsSync(socket)) {
            return new Promise((resolve) => setTimeout(resolve, ATTACH_RETRY_MS)).then(() => this.connect(socket, triesLeft - 1));
        }
        const { attach }: typeof import("neovim") = require("neovim");
        return Promise.resolve(attach({ socket }));
    }

    /** Neovim answers while it still reads the user's init files; it is of use once it is through with them. */
    private started(nvim: NeovimClient, triesLeft: number): Promise<NeovimClient> {
        return nvim.eval("v:vim_did_enter").then((entered) =>
            entered === 1 || triesLeft === 0
                ? nvim
                : new Promise((resolve) => setTimeout(resolve, ATTACH_RETRY_MS)).then(() => this.started(nvim, triesLeft - 1)));
    }

    /** Give the waiting neovim its text, as if it had been started on it: nothing to undo, the cursor in place. */
    private load(text: string, row: number, column: number, colours: Colours | null) {
        // the empty column on the left is said again: a colour scheme may have painted it since neovim started
        const paint = colours === null
            ? " | highlight! link FoldColumn Normal"
            : ["Normal", "FoldColumn"].map((group) => ` | highlight! ${group} guifg=${colours.foreground} guibg=${colours.background}`).join("");
        this.lines = text.split("\n");
        const lines = [...this.lines];
        this.fit();
        this.terminal.focus();
        this.nvim
            .then((nvim) =>
                nvim.command("let g:sge_undolevels = &undolevels | set undolevels=-1")
                    .then(() => nvim.request("nvim_buf_set_lines", [0, 0, -1, false, lines]))
                    // with the window still small the cursor would scroll the text, and it would stay scrolled
                    .then(() => this.sized(nvim, SIZE_TRIES))
                    // by character, not by byte: the column is one of the text as drawn
                    .then(() => nvim.command(
                        `let &undolevels = g:sge_undolevels | set nomodified | call setcursorcharpos(${row + 1}, ${column + 1})` +
                        ` | call winrestview({'topline': 1, 'leftcol': 0})${paint}`,
                    )))
            .catch(() => {
                // no neovim: the editor shows what the terminal has, and closes with the text unchanged
            })
            .then(() => {
                if (!this.closed) {
                    this.loaded = true;
                    this.typed.forEach((data) => this.process.write(data));
                    this.typed = [];
                    this.follow();
                    setTimeout(() => this.reveal(), REVEAL_TIMEOUT_MS);
                }
            });
    }

    private reveal() {
        if (!this.shown && !this.closed) {
            this.shown = true;
            this.element.style.opacity = "1";
            this.element.style.pointerEvents = "auto";
            this.onShown();
        }
    }

    /** Take over the buffer's changes as they happen. */
    private follow() {
        this.nvim.then((nvim) => nvim.buffer).then((buffer) => {
            buffer.listen("lines", (_buffer: unknown, _tick: number, first: number, last: number, replacement: string[]) => {
                this.lines.splice(first, last - first, ...replacement);
                if (this.lines.length === 0) this.lines = [""];
                this.changed();
            });
            // what was typed before this point caused no event
            return buffer.lines;
        }).then((lines) => this.take(lines)).catch(() => {
            // neovim went away meanwhile; the text stays as it was last told
        });
    }

    private take(lines: string[]) {
        this.lines = lines.length > 0 ? lines : [""];
        this.changed();
    }

    private changed() {
        if (!this.closed) {
            this.fit();
            this.onText(this.text);
        }
    }

    /** As many rows and columns as the text has now, or as few as neovim can do with; [place] cuts off the rest. */
    private fit() {
        const columns = Math.max(this.columns, MIN_COLUMNS);
        const rows = this.rows;
        if (this.terminal.cols !== columns || this.terminal.rows !== rows) {
            this.terminal.resize(columns, rows);
            this.process.resize(columns, rows);
            // if it did scroll meanwhile: all of the text fits again, once neovim has the new size
            this.nvim.then((nvim) => this.sized(nvim, SIZE_TRIES)).then((nvim) => nvim.call("winrestview", [{ topline: 1, leftcol: 0 }])).catch(() => {});
        }
    }

    /** Neovim learns of its terminal's size some time after the terminal has it; what depends on the size waits here. */
    private sized(nvim: NeovimClient, triesLeft: number): Promise<NeovimClient> {
        return nvim.eval("[&columns, &lines]").then((size) => {
            const [columns, rows] = size as number[];
            return (columns === this.terminal.cols && rows === this.terminal.rows) || triesLeft === 0
                ? nvim
                : new Promise((resolve) => setTimeout(resolve, SIZE_RETRY_MS)).then(() => this.sized(nvim, triesLeft - 1));
        });
    }

    /** Put the terminal at [place], stretched so that its cells have the size given there. */
    place(place: Place) {
        const screen = this.element.querySelector(".xterm-screen") as HTMLElement | null;
        const width = screen?.clientWidth ?? 0;
        const height = screen?.clientHeight ?? 0;
        if (width > 0 && height > 0) {
            const cellWidth = width / this.terminal.cols;
            const cellHeight = height / this.terminal.rows;
            const sx = place.cellWidth / cellWidth;
            const sy = place.cellHeight / cellHeight;
            // only the text's own rows show, and its columns with one more on either side
            this.element.style.width = `${cellWidth * (this.textColumns + 2 * PAD_COLUMNS)}px`;
            this.element.style.height = `${cellHeight * this.lines.length}px`;
            this.element.style.left = `${place.left - PAD_COLUMNS * place.cellWidth}px`;
            this.element.style.top = `${place.top}px`;
            this.element.style.transform = `scale(${sx}, ${sy})`;
        }
    }

    /** As if the user had typed [data]. */
    type(data: string) {
        if (this.loaded) this.process.write(data);
        else this.typed.push(data);
    }

    /**
     * Close the editor and tell the text it held. Neovim is asked
     * once more: the user may have typed faster than the changes
     * were told. If it does not answer, the text as last told stands.
     */
    close(): Promise<string> {
        if (this.closed) return Promise.resolve(this.text);
        this.closed = true;
        this.element.style.display = "none";
        // a neovim that had not got the text yet has nothing to tell
        const asked = !this.loaded ? Promise.resolve() : this.nvim.then((nvim) => nvim.buffer).then((buffer) => buffer.lines).then((lines) => {
            this.lines = lines.length > 0 ? lines : [""];
        });
        const waited = new Promise<void>((resolve) => setTimeout(resolve, CLOSE_TIMEOUT_MS));
        return Promise.race([asked, waited]).catch(() => {}).then(() => {
            this.discard();
            return this.text;
        });
    }

    private discard() {
        this.closed = true;
        this.nvim.then((nvim) => nvim.quit()).catch(() => {});
        // Neovim is a terminal program and a server behind it, in a session of its own; the server may outlive the terminal.
        // Not SIGKILL: a neovim packed as an AppImage leaves the process that mounts it behind then.
        const kill = (pid: number) => {
            try {
                if (pid > 1 || pid < -1) process.kill(pid, "SIGTERM");
            } catch {
                // gone already
            }
        };
        kill(-this.process.pid);
        kill(this.serverPid);
        this.terminal.dispose();
        this.element.remove();
        fs.rmSync(this.directory, { recursive: true, force: true });
    }
}
