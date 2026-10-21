import { Terminal } from "@xterm/xterm";
import type { IPty } from "node-pty";
import type { PaintedCell, Painting } from "./gridEditor";

// A terminal node: the user's shell in a terminal as big as the node. The shell is started when the node is first entered
// and then goes on, shown and running, whether the keys are the node's or the graph's; it ends when the shell does, when
// the node goes away or with the window.
//
// The terminal itself is not seen: it keeps what the shell printed and takes the keys, and what it holds is drawn in the
// node as any node's text is ([painting]), so wherever nodes are drawn: flat, with depth, in a headset.
//
// The shell is started by who looks at the graph and is given nothing of the document: not the node's text, not a command.

/** The colours a terminal names by number: the 16 usual ones, a cube of 216, and 24 greys. */
const PALETTE: string[] = (() => {
    const hex = (r: number, g: number, b: number) => `#${[r, g, b].map((c) => c.toString(16).padStart(2, "0")).join("")}`;
    const usual = [
        "#2e3436", "#cc0000", "#4e9a06", "#c4a000", "#3465a4", "#75507b", "#06989a", "#d3d7cf",
        "#555753", "#ef2929", "#8ae234", "#fce94f", "#729fcf", "#ad7fa8", "#34e2e2", "#eeeeec",
    ];
    const step = (n: number) => n === 0 ? 0 : 55 + 40 * n;
    const cube = Array.from({ length: 216 }, (_, i) => hex(step(Math.floor(i / 36)), step(Math.floor(i / 6) % 6), step(i % 6)));
    const greys = Array.from({ length: 24 }, (_, i) => hex(8 + 10 * i, 8 + 10 * i, 8 + 10 * i));
    return [...usual, ...cube, ...greys];
})();

/** How a program asked to be told of the mouse (DECSET 1005, 1006, 1015); "x10" when it named no way. */
type MouseEncoding = "x10" | "utf8" | "sgr" | "urxvt";
const MOUSE_ENCODINGS: { [mode: number]: MouseEncoding } = { 1005: "utf8", 1006: "sgr", 1015: "urxvt" };

/** The numbers of a mouse report: a button let go (but in SGR), the wheel up and down, and what is added for a drag. */
const MOUSE_RELEASED = 3;
const WHEEL_UP = 64;
const WHEEL_DOWN = 65;
const MOUSE_DRAG = 32;
/** The furthest column or row the encoding can tell of, from 1. */
const MOUSE_FURTHEST: { [encoding in MouseEncoding]?: number } = { x10: 223, utf8: 2015 };

type Held = { ctrl: boolean; alt: boolean; shift: boolean };
/** A cell of all the buffer: its row counted with what was scrolled back. */
type Spot = { row: number; column: number };
const NOTHING_HELD: Held = { ctrl: false, alt: false, shift: false };

/** How many lines a turn of the wheel scrolls back through what was printed. */
const WHEEL_LINES = 3;

/** What a terminal shows, and for each row what it looks like, as one string: two rows that look alike have the same. */
export interface TerminalPainting extends Painting {
    rowKeys: string[];
}

export class TerminalNode {
    private readonly element = document.createElement("div");
    private readonly terminal: Terminal;
    private readonly process: IPty;
    private ended = false;
    private focused = false;
    private encoding: MouseEncoding = "x10";
    /** The cell a drag was last told of: a move within it is not told again. */
    private dragged: string | null = null;
    /** Where a drag that selects started, while it goes on; and what is selected, from where it started to where it went. */
    private anchor: Spot | null = null;
    private selection: { from: Spot; to: Spot } | null = null;
    /** What the terminal shows, as it was last asked for, and in which colours; null once it shows something else. */
    private painted: { colours: string; painting: TerminalPainting } | null = null;

    /** [onEnd] is told when the shell is gone, by itself or because this was closed; [changed] when what the terminal shows is other than it was. */
    constructor(host: HTMLElement, private readonly onEnd: () => void, private readonly changed: () => void) {
        // there for the keys, and not to be seen
        this.element.style.cssText = "position: fixed; left: 0; top: 0; width: 1px; height: 1px; overflow: hidden; opacity: 0; pointer-events: none;";
        host.appendChild(this.element);
        this.terminal = new Terminal({ cols: 80, rows: 24 });
        this.terminal.open(this.element);
        // loaded here, not bundled: node-pty is native code
        const pty: typeof import("node-pty") = require("node-pty");
        this.process = pty.spawn(process.env.SHELL || "/bin/sh", [], {
            name: "xterm-256color",
            cols: 80,
            rows: 24,
            cwd: process.cwd(),
            env: process.env as { [key: string]: string },
        });
        const other = () => {
            this.painted = null;
            this.changed();
        };
        this.process.onData((data) => this.terminal.write(data, other));
        this.terminal.onData((data) => this.process.write(data));
        this.terminal.onScroll(other);
        this.process.onExit(() => this.close());
        // xterm tells which mouse events a program asked for, but not in which way: that is followed here, and xterm too takes it
        const asked = (on: boolean) => (params: (number | number[])[]) => {
            params.forEach((mode) => {
                const named = typeof mode === "number" ? MOUSE_ENCODINGS[mode] : undefined;
                if (named !== undefined && on) this.encoding = named;
                else if (named !== undefined && this.encoding === named) this.encoding = "x10";
            });
            return false;
        };
        const reset = () => {
            this.encoding = "x10";
            return false;
        };
        this.terminal.parser.registerCsiHandler({ prefix: "?", final: "h" }, asked(true));
        this.terminal.parser.registerCsiHandler({ prefix: "?", final: "l" }, asked(false));
        this.terminal.parser.registerCsiHandler({ intermediates: "!", final: "p" }, reset);
        this.terminal.parser.registerEscHandler({ final: "c" }, reset);
    }

    /** As many columns and rows as the node has room for. */
    size(columns: number, rows: number) {
        const wide = Math.max(2, Math.floor(columns));
        const high = Math.max(1, Math.floor(rows));
        if (!this.ended && (wide !== this.terminal.cols || high !== this.terminal.rows)) {
            this.terminal.resize(wide, high);
            this.process.resize(wide, high);
            this.painted = null;
        }
    }

    /** What the terminal shows now, cell by cell, in [foreground] on [background] where the shell names no colours. The same object until it shows something else. */
    painting(foreground: string, background: string): TerminalPainting {
        const colours = `${foreground} ${background}`;
        if (this.painted === null || this.painted.colours !== colours) {
            const buffer = this.terminal.buffer.active;
            // one cell read again and again: a new one for each would be a lot of them
            const reused = buffer.getNullCell();
            const colour = (rgb: boolean, palette: boolean, value: number, usual: string) =>
                rgb ? `#${value.toString(16).padStart(6, "0")}` : palette ? PALETTE[value] ?? usual : usual;
            const rowKeys: string[] = [];
            const range = this.ordered();
            const selected = (row: number, column: number) => range !== null &&
                (row > range[0].row || (row === range[0].row && column >= range[0].column)) &&
                (row < range[1].row || (row === range[1].row && column <= range[1].column));
            const rows = Array.from({ length: this.terminal.rows }, (_, y) => {
                const line = buffer.getLine(buffer.viewportY + y);
                let key = "";
                const row = Array.from({ length: this.terminal.cols }, (_, x): PaintedCell => {
                    const cell = line?.getCell(x, reused);
                    const fg = cell === undefined ? foreground : colour(cell.isFgRGB(), cell.isFgPalette(), cell.getFgColor(), foreground);
                    const bg = cell === undefined ? background : colour(cell.isBgRGB(), cell.isBgPalette(), cell.getBgColor(), background);
                    // the cursor, while the keys are the shell's, and what is selected: the cell the other way around
                    const cursor = this.focused && buffer.viewportY === buffer.baseY && x === buffer.cursorX && y === buffer.cursorY;
                    const turned = ((cell !== undefined && cell.isInverse() !== 0) !== cursor) !== selected(buffer.viewportY + y, x);
                    const bold = cell !== undefined && cell.isBold() !== 0;
                    const italic = cell !== undefined && cell.isItalic() !== 0;
                    const underline = cell !== undefined && cell.isUnderline() !== 0;
                    const text = cell?.getChars() || " ";
                    key += `${text}\u0001${fg}\u0001${bg}\u0001${turned ? 1 : 0}${bold ? 1 : 0}${italic ? 1 : 0}${underline ? 1 : 0}`;
                    return { text, foreground: turned ? bg : fg, background: turned ? fg : bg, bold, italic, underline };
                });
                rowKeys.push(key);
                return row;
            });
            this.painted = { colours, painting: { rows, background, rowKeys } };
        }
        return this.painted.painting;
    }

    /** As if the user had typed [data]. */
    type(data: string) {
        if (!this.ended) this.process.write(data);
    }

    get columns(): number {
        return this.terminal.cols;
    }

    get rows(): number {
        return this.terminal.rows;
    }

    /**
     * The mouse at [column], [row] (from 0) of the terminal, [button] as the DOM numbers them. A drag of the left button
     * pressed with Shift selects the text, which is then what the middle button pastes elsewhere; any other press lets go
     * of what was selected. Otherwise it is told to the program in it when it asked for the mouse, in the way it asked.
     */
    mouse(action: "press" | "drag" | "release", button: number, row: number, column: number, held: Held) {
        const at = { row: this.terminal.buffer.active.viewportY + row, column };
        const selects = action === "press" ? button === 0 && held.shift : this.anchor !== null;
        if (action === "press" && this.selection !== null) this.show(() => (this.selection = null));
        if (selects) this.select(action, at);
        else this.tell(action, button, row, column, held);
    }

    /** Select from where the drag started to [at] (a row of all the buffer, with what was scrolled back); when let go, that is the primary selection. */
    private select(action: "press" | "drag" | "release", at: Spot) {
        const from = this.anchor ?? at;
        this.anchor = action === "release" ? null : from;
        if (action === "drag") this.show(() => (this.selection = { from, to: at }));
        const text = action === "release" ? this.selected() : "";
        if (text !== "") require("electron").clipboard.writeText(text, "selection");
    }

    /** The selected text: rows that go on in the next one (wrapped) without a line end, and nothing at the end of a row that was not printed. */
    private selected(): string {
        const range = this.ordered();
        const buffer = this.terminal.buffer.active;
        const text = (first: Spot, last: Spot) => Array.from({ length: last.row - first.row + 1 }, (_, i) => first.row + i).map((row) => {
            const end = row === last.row ? last.column + 1 : this.terminal.cols;
            const part = buffer.getLine(row)?.translateToString(end === this.terminal.cols, row === first.row ? first.column : 0, end) ?? "";
            const goesOn = row < last.row && buffer.getLine(row + 1)?.isWrapped === true;
            return row === last.row || goesOn ? part : `${part}\n`;
        }).join("");
        return range === null ? "" : text(...range);
    }

    /** Where the selection begins and ends, in the order of the text; null when nothing is selected. */
    private ordered(): [Spot, Spot] | null {
        const s = this.selection;
        const forwards = s !== null && (s.from.row < s.to.row || (s.from.row === s.to.row && s.from.column <= s.to.column));
        return s === null ? null : forwards ? [s.from, s.to] : [s.to, s.from];
    }

    /** Make [change] to what is shown, and show it. */
    private show(change: () => void) {
        change();
        this.painted = null;
        this.changed();
    }

    /** The mouse told to the program in the terminal when it asked for it, in the way it asked; nothing otherwise. */
    private tell(action: "press" | "drag" | "release", button: number, row: number, column: number, held: Held) {
        const mode = this.terminal.modes.mouseTrackingMode;
        const wanted = mode === "x10" ? action === "press" : mode === "vt200" ? action !== "drag" : mode !== "none";
        const cell = `${row} ${column}`;
        const again = action === "drag" && this.dragged === cell;
        this.dragged = action === "drag" ? cell : null;
        if (wanted && !again && button <= 2) this.report(button + (action === "drag" ? MOUSE_DRAG : 0), action === "release", row, column, held);
    }

    /**
     * The wheel over [column], [row]: the program's when it asked for the mouse; arrow keys to a program that has the whole
     * screen (less, tmux), which has nothing to scroll back through; otherwise it scrolls back through what was printed.
     */
    wheel(direction: "up" | "down", row: number, column: number) {
        const mode = this.terminal.modes.mouseTrackingMode;
        if (mode !== "none" && mode !== "x10") {
            this.report(direction === "up" ? WHEEL_UP : WHEEL_DOWN, false, row, column, NOTHING_HELD);
        } else if (this.terminal.buffer.active.type === "alternate") {
            const arrow = `\x1b${this.terminal.modes.applicationCursorKeysMode ? "O" : "["}${direction === "up" ? "A" : "B"}`;
            this.type(arrow.repeat(WHEEL_LINES));
        } else {
            this.terminal.scrollLines(direction === "up" ? -WHEEL_LINES : WHEEL_LINES);
        }
    }

    /**
     * Tell the program of the mouse at [row], [column] (from 0): [code] is the button, the wheel's direction, and a drag, as
     * reports number them; [released] for a button let go. xterm tells of the mouse only over its own element, which is not
     * seen here, so the report is made here, in the way the program asked for. A cell further than that way can tell of is not told.
     */
    private report(code: number, released: boolean, row: number, column: number, held: Held) {
        const modifiers = this.terminal.modes.mouseTrackingMode === "x10" ? 0 : (held.shift ? 4 : 0) + (held.alt ? 8 : 0) + (held.ctrl ? 16 : 0);
        const x = column + 1;
        const y = row + 1;
        const button = (released && this.encoding !== "sgr" ? MOUSE_RELEASED : code) + modifiers;
        const fits = Math.max(x, y) <= (MOUSE_FURTHEST[this.encoding] ?? Infinity);
        const characters = `\x1b[M${String.fromCharCode(button + 32, x + 32, y + 32)}`;
        const report = this.encoding === "sgr" ? `\x1b[<${button};${x};${y}${released ? "m" : "M"}`
            : this.encoding === "urxvt" ? `\x1b[${button + 32};${x};${y}M`
            // the usual way is a byte each, not as UTF-8 would have them
            : this.encoding === "x10" ? Buffer.from(characters, "latin1")
            : characters;
        if (fits && !this.ended) this.process.write(report);
    }

    /** The keys are the shell's from now on ([on]), or no longer. */
    focus(on: boolean) {
        this.focused = on;
        this.painted = null;
        if (on) this.terminal.focus();
        else this.terminal.blur();
        this.changed();
    }

    close() {
        if (!this.ended) {
            this.ended = true;
            try {
                this.process.kill();
            } catch {
                // gone already
            }
            this.terminal.dispose();
            this.element.remove();
            this.onEnd();
        }
    }
}
