import { Terminal } from "@xterm/xterm";
import type { IPty } from "node-pty";

// A terminal node: the user's shell in a terminal laid over the node, as big as the node. The shell is started when the node
// is first entered and then goes on, shown and running, whether the keys are the node's or the graph's; it ends when the
// shell does, when the node goes away or with the window.
//
// The shell is started by who looks at the graph and is given nothing of the document: not the node's text, not a command.

/** The terminal's own font size; it is shown bigger or smaller with the node, so this only sets how sharp the letters are. */
const FONT_PX = 16;

/** Where a node's terminal goes, in page pixels: the corner and the size of the room in the node, and the font's size there. */
export interface Room {
    left: number;
    top: number;
    width: number;
    height: number;
    fontPx: number;
}

export class TerminalNode {
    private readonly element = document.createElement("div");
    private readonly terminal: Terminal;
    private readonly process: IPty;
    private ended = false;

    /** [onEnd] is told when the shell is gone, by itself or because this was closed; [again] asks to be placed once more. */
    constructor(host: HTMLElement, font: string, private readonly onEnd: () => void, private readonly again: () => void) {
        this.element.style.cssText = "position: fixed; transform-origin: 0 0; overflow: hidden; display: none;";
        host.appendChild(this.element);
        this.terminal = new Terminal({ fontFamily: font, fontSize: FONT_PX, cols: 80, rows: 24 });
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
        this.process.onData((data) => this.terminal.write(data));
        this.terminal.onData((data) => this.process.write(data));
        this.process.onExit(() => this.close());
    }

    /** Show the terminal in [room], with as many rows and columns as fit there; null: it is not to be seen now (it runs on). */
    place(room: Room | null) {
        // shown first: what is not shown has no size to measure
        this.element.style.display = room === null ? "none" : "block";
        const screen = this.element.querySelector(".xterm-screen") as HTMLElement | null;
        const cellWidth = (screen?.clientWidth ?? 0) / this.terminal.cols;
        const cellHeight = (screen?.clientHeight ?? 0) / this.terminal.rows;
        // the terminal has not drawn itself yet: until it has, it is not seen, and is asked about again
        this.element.style.visibility = cellWidth > 0 && cellHeight > 0 ? "visible" : "hidden";
        if (room !== null && !(cellWidth > 0 && cellHeight > 0)) requestAnimationFrame(this.again);
        if (room !== null && cellWidth > 0 && cellHeight > 0) {
            const scale = room.fontPx / FONT_PX;
            const columns = Math.max(2, Math.floor(room.width / scale / cellWidth));
            const rows = Math.max(1, Math.floor(room.height / scale / cellHeight));
            if (columns !== this.terminal.cols || rows !== this.terminal.rows) {
                this.terminal.resize(columns, rows);
                this.process.resize(columns, rows);
            }
            this.element.style.left = `${room.left}px`;
            this.element.style.top = `${room.top}px`;
            this.element.style.width = `${room.width / scale}px`;
            this.element.style.height = `${room.height / scale}px`;
            this.element.style.transform = `scale(${scale})`;
        }
    }

    /** The keys are the shell's from now on ([on]), or no longer. While they are not, the pointer goes through to the graph. */
    focus(on: boolean) {
        this.element.style.pointerEvents = on ? "auto" : "none";
        if (on) this.terminal.focus();
        else this.terminal.blur();
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
