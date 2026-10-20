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

/** xterm's numbers for the mouse (its `CoreMouseButton` and `CoreMouseAction`): no button, the wheel, and what happened. */
const MOUSE_NONE = 3;
const MOUSE_WHEEL = 4;
const MOUSE_ACTIONS = { release: 0, press: 1, drag: 32 };

/** How many lines a turn of the wheel scrolls back through what was printed. */
const WHEEL_LINES = 3;

export class TerminalNode {
    private readonly element = document.createElement("div");
    private readonly terminal: Terminal;
    private readonly process: IPty;
    private ended = false;
    private focused = false;
    /** What the terminal shows, as it was last asked for, and in which colours; null once it shows something else. */
    private painted: { colours: string; painting: Painting } | null = null;

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
    painting(foreground: string, background: string): Painting {
        const colours = `${foreground} ${background}`;
        if (this.painted === null || this.painted.colours !== colours) {
            const buffer = this.terminal.buffer.active;
            const rows = Array.from({ length: this.terminal.rows }, (_, y) => {
                const line = buffer.getLine(buffer.viewportY + y);
                return Array.from({ length: this.terminal.cols }, (_, x): PaintedCell => {
                    const cell = line?.getCell(x);
                    const colour = (rgb: boolean, palette: boolean, value: number, usual: string) =>
                        rgb ? `#${value.toString(16).padStart(6, "0")}` : palette ? PALETTE[value] ?? usual : usual;
                    const fg = cell === undefined ? foreground : colour(cell.isFgRGB(), cell.isFgPalette(), cell.getFgColor(), foreground);
                    const bg = cell === undefined ? background : colour(cell.isBgRGB(), cell.isBgPalette(), cell.getBgColor(), background);
                    // the cursor, while the keys are the shell's: the cell the other way around
                    const cursor = this.focused && buffer.viewportY === buffer.baseY && x === buffer.cursorX && y === buffer.cursorY;
                    const turned = (cell !== undefined && cell.isInverse() !== 0) !== cursor;
                    return {
                        text: cell?.getChars() || " ",
                        foreground: turned ? bg : fg,
                        background: turned ? fg : bg,
                        bold: cell !== undefined && cell.isBold() !== 0,
                        italic: cell !== undefined && cell.isItalic() !== 0,
                        underline: cell !== undefined && cell.isUnderline() !== 0,
                    };
                });
            });
            this.painted = { colours, painting: { rows, background } };
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
     * The mouse at [column], [row] (from 0) of the terminal, [button] as the DOM numbers them: told to the program in it
     * when it asked for the mouse, in the way it asked (which xterm knows); nothing otherwise.
     */
    mouse(action: "press" | "drag" | "release", button: number, row: number, column: number, held: { ctrl: boolean; alt: boolean; shift: boolean }) {
        if (this.terminal.modes.mouseTrackingMode !== "none") {
            this.report({ col: column, row, button: Math.min(button, MOUSE_NONE), action: MOUSE_ACTIONS[action], ...held });
        }
    }

    /** The wheel over [column], [row]: the program's when it asked for the mouse; otherwise it scrolls back through what was printed. */
    wheel(direction: "up" | "down", row: number, column: number) {
        if (this.terminal.modes.mouseTrackingMode !== "none") {
            this.report({ col: column, row, button: MOUSE_WHEEL, action: direction === "up" ? 0 : 1, ctrl: false, alt: false, shift: false });
        } else {
            this.terminal.scrollLines(direction === "up" ? -WHEEL_LINES : WHEEL_LINES);
        }
    }

    // xterm encodes what a program asked to be told of the mouse only for the mouse over its own element, which is not
    // seen here: its core's mouse service is asked directly. Not public: a newer xterm may move it, and then the mouse is lost.
    private report(event: { col: number; row: number; button: number; action: number; ctrl: boolean; alt: boolean; shift: boolean }) {
        try {
            (this.terminal as unknown as { _core: { coreMouseService: { triggerMouseEvent(e: object): boolean } } })._core.coreMouseService.triggerMouseEvent(event);
        } catch {
            // no such service: the program is told nothing
        }
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
