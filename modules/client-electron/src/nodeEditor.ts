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

/** Where the terminal goes, in page pixels: the corner of the text and the size of a character cell. */
export interface Place {
    left: number;
    top: number;
    cellWidth: number;
    cellHeight: number;
}

/** The terminal's own font size; it is stretched to the node's, so this only sets how sharp the letters are. */
const TERMINAL_FONT_PX = 16;

/**
 * Room beyond the text, so that neovim need not scroll before the
 * terminal has grown: the cursor behind the last character plus
 * the character typed next, and the line a new line opens.
 */
const SPARE_COLUMNS = 2;
const SPARE_ROWS = 1;

/** Neovim draws at least this much whatever size it is given. */
const MIN_COLUMNS = 12;
const MIN_ROWS = 3;

const NVIM = process.env.SGE_NVIM || "nvim";

/** How often and for how long to look for neovim's socket after starting it. */
const ATTACH_RETRY_MS = 50;
const ATTACH_TRIES = 100;

/** How long closing waits for neovim to tell its text. */
const CLOSE_TIMEOUT_MS = 3000;

// Shown in the node there must be the text and nothing else: no status line, no command line, no columns beside the text.
const BARE = "set laststatus=0 showtabline=0 cmdheight=0 noruler noshowcmd noshowmode signcolumn=no nonumber norelativenumber foldcolumn=0 nowrap " +
    "noswapfile sidescrolloff=0 shortmess+=IF";

export class NodeEditor {
    private readonly element = document.createElement("div");
    private readonly terminal: Terminal;
    private readonly process: IPty;
    private readonly directory = fs.mkdtempSync(path.join(os.tmpdir(), "sge-node-"));
    private readonly nvim: Promise<NeovimClient>;
    private lines: string[];
    private closed = false;

    /**
     * Opens neovim on [text] with the cursor at [row], [column] (from 0).
     * [onText] is told the text whenever it changes; [onExit] when neovim ends by itself (`:q`).
     */
    constructor(
        host: HTMLElement,
        font: string,
        text: string,
        row: number,
        column: number,
        private readonly onText: (text: string) => void,
        private readonly onExit: () => void,
    ) {
        this.lines = text.split("\n");
        const file = path.join(this.directory, "node.txt");
        const socket = path.join(this.directory, "nvim.sock");
        fs.writeFileSync(file, text);

        this.element.style.cssText = "position: fixed; transform-origin: 0 0; overflow: hidden;";
        host.appendChild(this.element);
        this.terminal = new Terminal({
            fontFamily: font,
            fontSize: TERMINAL_FONT_PX,
            cols: Math.max(this.columns, MIN_COLUMNS),
            rows: this.rows,
            scrollback: 0,
        });
        this.terminal.open(this.element);

        // loaded here, not bundled: node-pty is native code, and neovim's client expects to be a Node module
        const pty: typeof import("node-pty") = require("node-pty");
        const cursor = `call cursor(${row + 1}, ${column + 1})`;
        this.process = pty.spawn(NVIM, ["--listen", socket, "-c", BARE, "-c", cursor, file], {
            name: "xterm-256color",
            cols: Math.max(this.columns, MIN_COLUMNS),
            rows: this.rows,
            cwd: this.directory,
            env: process.env as { [key: string]: string },
        });
        this.process.onData((data) => this.terminal.write(data));
        this.terminal.onData((data) => this.process.write(data));
        this.process.onExit(() => {
            if (!this.closed) this.onExit();
        });
        this.terminal.focus();
        this.nvim = this.connect(socket, ATTACH_TRIES);
        this.follow();
    }

    /** The text as far as neovim has told. */
    get text(): string {
        return this.lines.join("\n");
    }

    private get columns(): number {
        return Math.max(1, ...this.lines.map((l) => l.length)) + SPARE_COLUMNS;
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
            // if it did scroll meanwhile: all of the text fits again
            this.nvim.then((nvim) => nvim.call("winrestview", [{ topline: 1, leftcol: 0 }])).catch(() => {});
        }
    }

    private get rows(): number {
        return Math.max(this.lines.length + SPARE_ROWS, MIN_ROWS);
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
            // only the text's own rows and columns show
            this.element.style.width = `${cellWidth * this.columns}px`;
            this.element.style.height = `${cellHeight * this.lines.length}px`;
            this.element.style.left = `${place.left}px`;
            this.element.style.top = `${place.top}px`;
            this.element.style.transform = `scale(${sx}, ${sy})`;
        }
    }

    /** As if the user had typed [data]. */
    type(data: string) {
        this.process.write(data);
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
        const asked = this.nvim.then((nvim) => nvim.buffer).then((buffer) => buffer.lines).then((lines) => {
            this.lines = lines.length > 0 ? lines : [""];
        });
        const waited = new Promise<void>((resolve) => setTimeout(resolve, CLOSE_TIMEOUT_MS));
        return Promise.race([asked, waited]).catch(() => {}).then(() => {
            this.nvim.then((nvim) => nvim.quit()).catch(() => {});
            this.process.kill();
            this.terminal.dispose();
            this.element.remove();
            fs.rmSync(this.directory, { recursive: true, force: true });
            return this.text;
        });
    }
}
