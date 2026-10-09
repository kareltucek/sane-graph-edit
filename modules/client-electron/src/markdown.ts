// Markdown nodes as pictures. The text of a node comes from a
// document and is not to be trusted with this page, which has Node:
// it is formatted in a frame of its own (render.html, render.ts)
// that is sandboxed, and comes back as an SVG. Here that SVG is
// only ever an image, in which nothing can run.

/** A formatted text, ready to draw; [width] and [height] are its size in pixels at [REFERENCE_FONT_PX]. */
export interface Picture {
    image: HTMLImageElement;
    width: number;
    height: number;
}

/** The font size the pictures are made at; they are scaled to a node's own when drawn. */
export const REFERENCE_FONT_PX = 16;

/** Kept pictures are let go of all at once when there are this many: simpler than deciding which are still needed. */
const MAX_PICTURES = 500;

interface Answer {
    id?: number;
    ready?: boolean;
    svg?: string;
    width?: number;
    height?: number;
    error?: string;
}

export class MarkdownPictures {
    private readonly frame = document.createElement("iframe");
    private readonly ready: Promise<void>;
    private readonly waiting = new Map<number, (answer: Answer) => void>();
    private readonly pictures = new Map<string, Picture | null>();
    private nextId = 0;

    /** [onArrived] is told when a picture that was asked for is there to be drawn. */
    constructor(private readonly onArrived: () => void) {
        // scripts and nothing else: no Node, no files, not this page's origin
        this.frame.setAttribute("sandbox", "allow-scripts");
        // laid out, or nothing in it has a size; out of sight
        this.frame.style.cssText = "position: fixed; left: -20000px; top: 0; width: 4000px; height: 4000px; border: none; visibility: hidden;";
        this.ready = new Promise((resolve) => {
            window.addEventListener("message", (event) => {
                const answer = event.data as Answer;
                if (event.source === this.frame.contentWindow) {
                    if (answer.ready) resolve();
                    else if (answer.id !== undefined) {
                        this.waiting.get(answer.id)?.(answer);
                        this.waiting.delete(answer.id);
                    }
                }
            });
        });
        // Its page is given whole, script and all: a frame without an origin may not read a script from a file.
        // (render.ts is built to render.js beside this page's own script.)
        const script: string = require("fs").readFileSync(require("path").join(__dirname, "render.js"), "utf8");
        this.frame.srcdoc =
            `<!doctype html><html><head><meta charset="utf-8"><style>html, body { margin: 0; } #stage { position: absolute; left: 0; top: 0; visibility: hidden; }` +
            `</style></head><body><div id="stage"></div><script>${script.replace(/<\/script/gi, "<\\/script")}</script></body></html>`;
        document.body.appendChild(this.frame);
    }

    /**
     * The picture of [markdown] set in [font], [foreground] on a
     * [dark] or light background, its lines broken at [maxWidth]
     * pixels; null until it has been made (it is asked for on first
     * use) and for a text that cannot be made a picture of.
     */
    get(markdown: string, font: string, foreground: string, dark: boolean, maxWidth: number): Picture | null {
        const key = [font, foreground, dark, maxWidth, markdown].join("\n");
        if (!this.pictures.has(key)) {
            if (this.pictures.size >= MAX_PICTURES) this.pictures.clear();
            this.pictures.set(key, null);
            const id = this.nextId++;
            this.ready.then(() => {
                this.waiting.set(id, (answer) => this.arrived(key, answer));
                this.frame.contentWindow?.postMessage({ id, markdown, font, fontPx: REFERENCE_FONT_PX, foreground, dark, maxWidth }, "*");
            });
        }
        return this.pictures.get(key) ?? null;
    }

    private arrived(key: string, answer: Answer) {
        const { svg, width, height } = answer;
        if (svg !== undefined && width !== undefined && height !== undefined && width > 0 && height > 0) {
            const image = new Image();
            image.onload = () => {
                this.pictures.set(key, { image, width, height });
                this.onArrived();
            };
            // as an image and never as markup: whatever is in it cannot run
            image.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
        }
    }
}
