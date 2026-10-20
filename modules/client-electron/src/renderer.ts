import { client, host } from "sane-graph-edit-editor-host";
import { Bitmaps, Recent } from "./bitmaps";
import { Canvas2D, Cells, GridEditor, PaintedCell, Painting, StatusPiece } from "./gridEditor";
import { MarkdownPictures, Picture, REFERENCE_FONT_PX } from "./markdown";
import { Colours, NodeEditor, PAD_COLUMNS, Place, TERMINAL_FONT_PX } from "./nodeEditor";
import { TerminalNode } from "./terminalNode";
import { NodePicture, Shell, shellColours, SpaceRenderer, SpaceScene } from "./space";
import { mockRoom, outlines, wallsOf } from "./room";
import { startVr, VrLive, VrScene, VrSurface } from "./vrServer";

// The page: draws what the client core says the view shows and
// passes the user's input to it. No graph logic in here.

interface SceneNode {
    id: string;
    x: number;
    y: number;
    width: number;
    height: number;
    textWidth: number;
    textHeight: number;
    lines: string[];
    shape: string;
    bg: string | null;
    fg: string | null;
    /** The colours are from the picker's palette. */
    palette: boolean;
    /** What the text is, and for code that names one, its language. */
    type: "plain" | "code" | "markdown" | "terminal";
    language: string | null;
    fontSize: number;
    /** How many times its sizes the node is drawn, around x, y: other than 1 for a node nearer or further than the plane the view is of. */
    depthScale: number;
    selected: boolean;
    /** Hidden, and seen only as a parent or a child of a shown node: drawn half as strong. */
    faint: boolean;
    /** The node has a size of its own: what does not fit in it is not shown, and it is resized by its corners. */
    manual: boolean;
}

interface SceneEdge {
    x1: number;
    y1: number;
    x2: number;
    y2: number;
    /** How many times as thick the line is, and as big the head, as in the plane the view is of: for an edge further away or nearer. */
    scale: number;
    headScale: number;
}

interface Scene {
    scale: number;
    offsetX: number;
    offsetY: number;
    nodes: SceneNode[];
    edges: SceneEdge[];
    /** The selection rectangle being dragged: two opposite corners. */
    selectionBox: SceneEdge | null;
    /** While `o` settles the whole graph: its systems, as they appear (z unused). */
    shells: Shell[];
}

/** How strong the circles of the shells are: they are seen through, not looked at. */
const SHELL_ALPHA = 0.6;

const FONT = "monospace";
const LINE_HEIGHT = 1.2;
/**
 * Between a node's text and its edge, in characters: beside the
 * text what the editor shows there and half a column around that,
 * above and below it half a row.
 */
const MARGIN_COLUMNS = PAD_COLUMNS + 0.5;
const MARGIN_ROWS = 0.5;
/** How far the space behind the graph is from the nodes' colour, so that they stand out. */
const CANVAS_CONTRAST = 0.15;

/** Nodes and lines that have no colour of their own. */
const PLAIN: Colours = { foreground: "#000000", background: "#ffffff" };
/** Neovim's colour scheme, once it is known; used unless the user's init file says `set nvim-theme=off`. */
let nvimColours: Colours | null = null;
let useNvimColours = true;

const colours = () => (useNvimColours && nvimColours !== null ? nvimColours : PLAIN);

const channels = (colour: string) => [1, 3, 5].map((at) => parseInt(colour.slice(at, at + 2), 16));

function isLight(colour: string): boolean {
    const rgb = channels(colour);
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2] > 127;
}

/** Behind the graph: a little darker than a light node colour, a little lighter than a dark one. */
function canvasColour(nodes: string): string {
    const rgb = channels(nodes);
    const towards = isLight(nodes) ? 0 : 255;
    return "#" + rgb.map((c) => Math.round(c + (towards - c) * CANVAS_CONTRAST).toString(16).padStart(2, "0")).join("");
}
const CORNER_RADIUS = 2;

/** Neovim's status line has at least this many columns, however long the name of the file in it. */
const MIN_STATUS_COLUMNS = 20;

/** What neovim calls the copy of a node's text it edits ([GridEditor]): in its status line the node's file is named instead. */
const NODE_FILE = "node.txt";
const ARROWHEAD_RADIUS = 3;
/** Text smaller than this on the screen is not drawn. */
const MIN_TEXT_PX = 3;

const canvas = document.getElementById("graph") as HTMLCanvasElement;
const flat = canvas.getContext("2d")!;
/** What is drawn on: the canvas, or for a while the picture of a node that is being made ([pictureOfNode]). */
let ctx: Canvas2D = flat;
/** The graph drawn with depth, below the canvas; made when 3D mode is first used. */
const spaceCanvas = document.getElementById("space") as HTMLCanvasElement;
let spaceRenderer: SpaceRenderer<SpaceSceneNode> | null = null;
const message = document.getElementById("message") as HTMLDivElement;

function say(text: string) {
    message.textContent = text;
    message.style.display = "block";
}

// Below the graph: the status line, the `:` line that opens over it, and above them the pane for messages of several lines.
// What they say and when they show is the client core's business.
const statusLine = document.getElementById("status") as HTMLDivElement;
const lineInput = document.getElementById("line") as HTMLInputElement;
const pane = document.getElementById("pane") as HTMLPreElement;

const lineOpen = () => lineInput.style.display === "block";
const paneOpen = () => pane.style.display === "block";

function showLine(text: string) {
    lineInput.value = text;
    lineInput.style.display = "block";
    lineInput.focus();
    lineInput.setSelectionRange(text.length, text.length);
}

function hideLine() {
    lineInput.style.display = "none";
    lineInput.blur();
}

/** The pane shows the list of what the `:` line can be completed to, not a message. */
let paneIsList = false;

/** Show [text] in the pane above the status line: a message, or ([list]) what the `:` line can be completed to. */
function showMessage(text: string, list = false, kept = false) {
    pane.textContent = text;
    pane.style.display = "block";
    pane.scrollTop = 0;
    paneIsList = list;
    paneIsKept = kept;
}

/** What the pane shows is to be copied from at leisure (an address, a PIN): only Escape puts it away, other keys do what they always do. */
let paneIsKept = false;

/** Hide the pane; with [list], only if it still shows the list: what the command said in its place stays. */
function hideMessage(list = false) {
    if (!list || paneIsList) pane.style.display = "none";
}

// The strip of tabs above the graph. Which tabs there are and what selecting and closing one does is the client core's business.
const tabStrip = document.getElementById("tabs") as HTMLDivElement;

function showTabs(app: client.JsApp) {
    const tabs = app.tabs() as { title: string; current: boolean }[];
    tabStrip.replaceChildren(...tabs.map((tab, index) => {
        const element = document.createElement("div");
        element.className = tab.current ? "tab current" : "tab";
        element.textContent = tab.title;
        element.addEventListener("click", () => app.select(index));
        // the middle button closes, as in a browser
        element.addEventListener("auxclick", (e) => {
            if (e.button === 1) app.close(index);
        });
        return element;
    }));
    const shown = tabs.find((tab) => tab.current);
    document.title = shown ? `${shown.title} — Sane Graph Edit` : "Sane Graph Edit";
}

/** The window is closing because the client core said so: nothing more to ask. */
let leaving = false;

// The style picker: colours, size and shape for the selected nodes. Which styles there are and what picking does is the client core's business.
const picker = document.getElementById("picker") as HTMLDivElement;
const pickerColours = document.getElementById("picker-colours") as HTMLDivElement;
const pickerLanguage = document.getElementById("picker-language") as HTMLInputElement;

const pickerOpen = () => picker.style.display === "flex";

/** Show the picker around the view position x, y, all of it inside the view. */
function showPicker(x: number, y: number) {
    picker.style.display = "flex";
    const bounds = canvas.getBoundingClientRect();
    const within = (centre: number, size: number, room: number) => Math.max(0, Math.min(centre - size / 2, room - size));
    picker.style.left = `${bounds.left + within(x, picker.offsetWidth, bounds.width)}px`;
    picker.style.top = `${bounds.top + within(y, picker.offsetHeight, bounds.height)}px`;
}

function hidePicker() {
    picker.style.display = "none";
}

/** The picker's colour buttons, as the theme shows the palette now. */
function fillPicker(view: client.JsView) {
    const offered = view.pickerStyles() as { columns: number; styles: { bg: string | null; fg: string | null; label: string }[] };
    pickerColours.style.gridTemplateColumns = `repeat(${offered.columns}, 1fr)`;
    pickerColours.replaceChildren();
    offered.styles.forEach((style, index) => {
        const button = document.createElement("button");
        button.textContent = style.label;
        // a style without colours is the default one
        button.style.background = style.bg ?? "var(--bg)";
        button.style.color = style.fg ?? "var(--fg)";
        button.addEventListener("click", () => view.pickColour(index));
        pickerColours.appendChild(button);
    });
}

function wirePicker(current: (action: (view: client.JsView) => void) => void) {
    picker.querySelectorAll<HTMLButtonElement>("button[data-size]").forEach((b) => b.addEventListener("click", () => current((view) => view.pickSize(Number(b.dataset.size)))));
    picker.querySelectorAll<HTMLButtonElement>("button[data-shape]").forEach((b) => b.addEventListener("click", () => current((view) => view.pickShape(b.dataset.shape!))));
    picker.querySelectorAll<HTMLButtonElement>("button[data-mode]").forEach((b) => b.addEventListener("click", () => current((view) => view.pickSizeMode(b.dataset.mode === "manual"))));
    // code is code in the language named beside it, when one is
    const typed = (type: string) => type === "code" && pickerLanguage.value.trim() !== "" ? `code-${pickerLanguage.value.trim()}` : type;
    picker.querySelectorAll<HTMLButtonElement>("button[data-type]").forEach((b) => b.addEventListener("click", () => current((view) => view.pickType(typed(b.dataset.type!)))));
    pickerLanguage.addEventListener("keydown", (e) => {
        // what is typed here is the language's name, not keys of the graph
        e.stopPropagation();
        if (e.key === "Enter") {
            current((view) => view.pickType(typed("code")));
            pickerLanguage.blur();
        } else if (e.key === "Escape") {
            pickerLanguage.blur();
            current((view) => view.pickerDismissed());
        }
    });
}

/** Show in the picker what the selected nodes are: which content, and of code the language. */
function markPickedType(view: client.JsView) {
    const type = view.pickedType;
    picker.querySelectorAll<HTMLButtonElement>("button[data-type]").forEach((b) => b.classList.toggle("picked", b.dataset.type === type.split("-")[0]));
    const mode = view.pickedSizeMode;
    picker.querySelectorAll<HTMLButtonElement>("button[data-mode]").forEach((b) => b.classList.toggle("picked", b.dataset.mode === mode));
    if (document.activeElement !== pickerLanguage) pickerLanguage.value = type.startsWith("code-") ? type.slice("code-".length) : "";
}

/** Keys that put a message away and do nothing else; any other key puts it away and means what it always does. */
const DISMISSING_KEYS = ["Escape", "Enter", "q"];

/**
 * Where the baseline is in a cell of the node's editor, as a
 * fraction of the cell's height: measured on a row built like the
 * terminal's, at its font size. The node's text is drawn on the
 * same, or it moves when editing starts; the font's box as a canvas
 * tells it puts the baseline lower, and so does HTML at other sizes.
 */
function editorBaseline(): number {
    const font = `font: ${TERMINAL_FONT_PX}px ${FONT};`;
    const sample = document.createElement("span");
    sample.style.cssText = `position: absolute; visibility: hidden; line-height: normal; ${font}`;
    sample.textContent = "W";
    const row = document.createElement("div");
    const cell = document.createElement("span");
    cell.style.cssText = "display: inline-block; height: 100%; vertical-align: top;";
    const mark = document.createElement("span");
    mark.style.cssText = "display: inline-block; width: 0; height: 0;";
    cell.append(mark, "W");
    row.appendChild(cell);
    document.body.append(sample, row);
    const height = Math.ceil(sample.getBoundingClientRect().height);
    row.style.cssText = `position: absolute; visibility: hidden; overflow: hidden; ${font} height: ${height}px; line-height: ${height}px;`;
    const baseline = (mark.getBoundingClientRect().top - row.getBoundingClientRect().top) / height;
    sample.remove();
    row.remove();
    return baseline;
}

/** The font's cell, as fractions of the font size; the client core sizes nodes with it. */
function measureGrid(): client.GridMetrics {
    const size = 100;
    ctx.font = `${size}px ${FONT}`;
    const m = ctx.measureText("M");
    const ascent = editorBaseline() * LINE_HEIGHT * size;
    return new client.GridMetrics(m.width / size, LINE_HEIGHT, ascent / size, MARGIN_COLUMNS * m.width / size, MARGIN_ROWS * LINE_HEIGHT);
}

/**
 * Draw [e] in a view at [scale] that is [width] by [height]. An
 * edge that is long on the screen is drawn fainter, so that the
 * long ones do not cover what they cross: fully drawn up to an
 * eighth of the view's shorter side, faintest from half its longer.
 */
function drawEdge(e: SceneEdge, scale: number, width: number, height: number) {
    const length = Math.hypot(e.x2 - e.x1, e.y2 - e.y1) * scale;
    const from = Math.min(width, height) * 0.125;
    const to = Math.max(width, height) * 0.5;
    const fade = to > from ? Math.min(1, Math.max(0, (length - from) / (to - from))) : 0;
    ctx.globalAlpha = 1 - fade * EDGE_FADE;
    ctx.lineWidth = e.scale;
    ctx.beginPath();
    ctx.moveTo(e.x1, e.y1);
    ctx.lineTo(e.x2, e.y2);
    ctx.stroke();
    if (e.headScale > 0) {
        ctx.beginPath();
        ctx.arc(e.x2, e.y2, ARROWHEAD_RADIUS * e.headScale, 0, 2 * Math.PI);
        ctx.fill();
    }
    ctx.globalAlpha = 1;
}

/** How much of an edge is taken away when it is faintest. */
const EDGE_FADE = 0.86;

/** What neovim calls the kind of text in [n] (its file type); empty for plain text and for a language that cannot be one. */
function filetypeOf(n: SceneNode): string {
    const named = n.type === "markdown" ? "markdown" : n.type === "code" ? (n.language ?? "") : "";
    return /^[A-Za-z0-9_]+$/.test(named) ? named : "";
}

/** Code as neovim highlights it, by what was painted: asked for once, kept, and drawn in place of the plain text when it is there. */
const paintings = new Recent<Painting | null>(10000);

/**
 * Neovim's painting of the text of [n], if [n] is code in a
 * language neovim is to highlight and the painting has arrived;
 * null otherwise. Asks for it on first use and repaints when it
 * comes.
 */
function paintingOf(n: SceneNode): Painting | null {
    // a terminal node whose shell runs shows what its terminal does
    const terminal = n.type === "terminal" ? shownTerminals?.get(n.id) : undefined;
    return terminal !== undefined ? terminal.painting(inkOf(n), n.bg ?? colours().background) : highlightingOf(n);
}

function highlightingOf(n: SceneNode): Painting | null {
    const filetype = n.type === "code" && useGridEditor ? filetypeOf(n) : "";
    const text = n.lines.join("\n");
    const background = n.bg ?? colours().background;
    const foreground = inkOf(n);
    const key = [filetype, background, foreground, text].join("\n");
    if (filetype !== "" && !paintings.has(key)) {
        paintings.set(key, null);
        GridEditor.paint(text, filetype, { background, foreground }).then((painting) => {
            paintings.set(key, painting);
            page.repaint();
        }).catch(() => {
            // no neovim, no highlighting: the text stays plain
        });
    }
    return filetype === "" ? null : paintings.get(key) ?? null;
}

/** Markdown nodes as pictures, made in a frame of their own. */
const markdownPictures = new MarkdownPictures(() => page.repaint());

/** The picture of the formatted text of [n], if [n] is markdown and the picture has been made; null otherwise. */
function pictureOf(n: SceneNode): Picture | null {
    // lines are broken where the source's longest line ends, so that the picture is about as wide as the text
    const maxWidth = Math.ceil((n.textWidth / n.fontSize) * REFERENCE_FONT_PX) + 2;
    return n.type === "markdown" ? markdownPictures.get(n.lines.join("\n"), FONT, inkOf(n), !isLight(n.bg ?? colours().background), maxWidth) : null;
}

/**
 * What a node shows inside its outline: a rectangle in the graph's
 * units, and [draw], which draws it there. Two with the same [key]
 * and of the same size look the same, so that a drawing of one can
 * be kept and shown for the other.
 */
interface Content {
    key: string;
    left: number;
    top: number;
    width: number;
    height: number;
    /** Draws it; when only a part is [seen] (from where to where, top to bottom), no more than that needs drawing. */
    draw: (to: Canvas2D, seen?: Seen) => void;
}

interface Seen {
    top: number;
    bottom: number;
}

/**
 * [picture] in the node [n]: at the size of the node's font, where
 * its text would start; smaller if it would not fit into the node,
 * and then in the middle of it.
 */
function pictureContent(n: SceneNode, picture: Picture): Content {
    const natural = n.fontSize / REFERENCE_FONT_PX;
    const inset = n.fontSize * 0.25;
    const fit = Math.min(1, (n.width - 2 * inset) / (picture.width * natural), (n.height - 2 * inset) / (picture.height * natural));
    const width = picture.width * natural * fit;
    const height = picture.height * natural * fit;
    const fitsText = width <= n.textWidth + 0.5 && height <= n.textHeight + 0.5;
    const left = fitsText ? n.x - n.textWidth / 2 : n.x - width / 2;
    const top = fitsText ? n.y - n.textHeight / 2 : n.y - height / 2;
    return { key: `picture ${nameOf(picture)}`, left, top, width, height, draw: (to) => to.drawImage(picture.image, left, top, width, height) };
}

/**
 * Where the text of [n] begins: in the middle of the node, which is as big as it needs; in a node that has a size of its own
 * ([SceneNode.manual]) at its top left, so that what does not fit is cut off at the end and not all around.
 */
function textCorner(n: SceneNode): { left: number; top: number } {
    const inset = n.fontSize * 0.5;
    return n.manual ? { left: n.x - n.width / 2 + inset, top: n.y - n.height / 2 + inset } : { left: n.x - n.textWidth / 2, top: n.y - n.textHeight / 2 };
}

/** The text of [n] in its cells: as neovim painted it if [painting] is given, plain otherwise. A little room around it for what reaches out of a cell. */
function textContent(n: SceneNode, grid: client.GridMetrics, painting: Painting | null): Content {
    const room = n.fontSize * 0.25;
    const { left: textLeft, top: textTop } = textCorner(n);
    // of a node with a size of its own, what is in the node: its text may be bigger or (a terminal's label) smaller than what it shows
    const box = n.manual
        ? { left: n.x - n.width / 2, top: n.y - n.height / 2, width: n.width, height: n.height }
        : { left: textLeft - room, top: textTop - room, width: n.textWidth + 2 * room, height: n.textHeight + 2 * room };
    const cells: Cells = {
        left: textLeft,
        top: textTop,
        cellWidth: grid.advance * n.fontSize,
        cellHeight: grid.lineHeight * n.fontSize,
        font: `${n.fontSize}px ${FONT}`,
        baseline: grid.ascent * n.fontSize,
        clip: box,
    };
    const ink = inkOf(n);
    return painting !== null
        ? { key: `painting ${nameOf(painting)} ${n.fontSize}`, ...box, draw: (to, seen) => GridEditor.drawPainting(to, cells, painting, undefined, seen) }
        : {
            key: ["text", n.fontSize, ink, ...n.lines].join("\n"),
            ...box,
            draw: (to, seen) => {
                to.fillStyle = ink;
                to.font = cells.font;
                n.lines.forEach((line, i) => {
                    const top = textTop + i * cells.cellHeight;
                    // a line more on each side: what reaches out of its cell
                    const drawn = seen === undefined || (top + 2 * cells.cellHeight >= seen.top && top - cells.cellHeight <= seen.bottom);
                    if (drawn) to.fillText(line, textLeft, top + cells.baseline);
                });
            },
        };
}

/** A name for each painting and picture, for the key of a drawing made from it. */
const names = new WeakMap<object, number>();
let lastName = 0;

function nameOf(thing: object): number {
    if (!names.has(thing)) names.set(thing, ++lastName);
    return names.get(thing)!;
}

/** Drawings of what nodes show, kept for a view that is zoomed out. */
const bitmaps = new Bitmaps();

/**
 * Draw [content] in a view at [scale] on a screen of [dpr] pixels
 * to one of the page's. Zoomed out, or not zoomed at all: from the
 * kept drawing of it, which is made if there is none. Closer than
 * the kept drawing is made for: as it is.
 */
function drawContent(content: Content, scene: View, dpr: number) {
    const needed = scene.scale * dpr;
    const finest = bitmaps.scaleOf(content.width, content.height, dpr);
    // half the finest scale as often as still leaves at least what the view needs
    const halvings = Math.max(0, Math.floor(Math.log2(finest * SAME_SCALE / needed)));
    // only for the flat canvas: a picture of a node that is being made is itself what is kept, and needs no drawing kept for it
    const bitmap = ctx === flat && needed <= finest * SAME_SCALE
        ? bitmaps.get(`${content.key}\n${content.width} ${content.height}`, content.width, content.height, finest / 2 ** halvings, (to) => {
            to.translate(-content.left, -content.top);
            content.draw(to);
        })
        : null;
    const kept = bitmap?.scale ?? finest;
    // drawn as it is: only the part in the view
    if (bitmap === null) content.draw(ctx, { top: -scene.offsetY / scene.scale, bottom: (ctx.canvas.height / dpr - scene.offsetY) / scene.scale });
    else if (needed >= kept / SAME_SCALE) {
        // pixel for pixel, at whole pixels: nothing is blurred
        ctx.save();
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.drawImage(bitmap.image, Math.round((content.left * scene.scale + scene.offsetX) * dpr), Math.round((content.top * scene.scale + scene.offsetY) * dpr));
        ctx.restore();
    } else ctx.drawImage(bitmap.image, content.left, content.top, bitmap.image.width / kept, bitmap.image.height / kept);
}

/** Two scales no further apart than this are the same one: zooming in and back out does not come to quite the same number. */
const SAME_SCALE = 1.0001;

/** The colour of the text and outline of [n]. A node with a background of its own and no text colour: the theme's text may not show on it. */
function inkOf(n: SceneNode): string {
    return n.fg ?? (n.bg === null ? colours().foreground : isLight(n.bg) ? "#000000" : "#ffffff");
}

/** A zoom and a pan: `screen = workspace * scale + offset`. */
interface View {
    scale: number;
    offsetX: number;
    offsetY: number;
}

/** The zoom and pan [n] is drawn with: the scene's, or, for a node out of the scene's plane, what shows it bigger or smaller around where it appears to be. */
function viewOf(n: SceneNode, scene: View): View {
    const scale = scene.scale * n.depthScale;
    return { scale, offsetX: scene.offsetX + n.x * (scene.scale - scale), offsetY: scene.offsetY + n.y * (scene.scale - scale) };
}

/** Draw [n]. Text too small to read is left out, unless [smallText]: a picture that is kept is not made again for being small. */
/** How strong a faint node is drawn. */
const FAINT = 0.5;
/** How big the corners of a selected node that is resized by them are drawn, in pixels. */
const GRIP_PX = 7;

function drawNode(n: SceneNode, grid: client.GridMetrics, scene: Scene, dpr: number, smallText = false) {
    const view = viewOf(n, scene);
    const scale = view.scale;
    ctx.setTransform(dpr * scale, 0, 0, dpr * scale, dpr * view.offsetX, dpr * view.offsetY);
    const left = n.x - n.width / 2;
    const top = n.y - n.height / 2;
    ctx.beginPath();
    if (n.shape === "oval") ctx.ellipse(n.x, n.y, n.width / 2, n.height / 2, 0, 0, 2 * Math.PI);
    else ctx.roundRect(left, top, n.width, n.height, CORNER_RADIUS);
    const ink = inkOf(n);
    if (n.faint) {
        // half of it over the background's colour: nothing shows through it, as through no node
        ctx.fillStyle = colours().background;
        ctx.fill();
        ctx.globalAlpha = FAINT;
    }
    ctx.fillStyle = n.bg ?? colours().background;
    ctx.fill();
    ctx.strokeStyle = ink;
    // a selected node's outline stays visible however far out the view is zoomed
    ctx.lineWidth = n.selected ? Math.max(2, 2.2 / scale) : 1;
    ctx.stroke();
    // the node being edited has its editor where the text would be, once that shows
    const shown = (smallText || n.fontSize * scale >= MIN_TEXT_PX) && !(n.id === editing?.id && editing.editor.shown);
    if (shown) {
        // a markdown node is its picture, once that is made; a code node what neovim painted, once that is
        const picture = pictureOf(n);
        ctx.save();
        if (n.manual) {
            // a node with a size of its own shows what fits in it
            ctx.beginPath();
            ctx.rect(left, top, n.width, n.height);
            ctx.clip();
        }
        drawContent(picture !== null ? pictureContent(n, picture) : textContent(n, grid, paintingOf(n)), view, dpr);
        ctx.restore();
    }
    if (n.manual && n.selected) {
        // where it is taken to be resized
        const grip = GRIP_PX / scale;
        ctx.fillStyle = ink;
        [[left, top], [left + n.width, top], [left, top + n.height], [left + n.width, top + n.height]].forEach(([x, y]) => ctx.fillRect(x - grip / 2, y - grip / 2, grip, grip));
    }
    ctx.globalAlpha = 1;
}

/** A node of the graph drawn with depth: where the view sees it, and how far away. */
type SpaceSceneNode = SceneNode & { z: number };

/** Units of the graph around a node in its picture: room for the outline of a selected one. */
const PICTURE_MARGIN = 3;

/** A picture of [n] as [drawNode] draws it, [pixelsPerUnit] pixels to a unit: what the graph drawn with depth shows of it. */
function pictureOfNode(n: SpaceSceneNode, grid: client.GridMetrics, pixelsPerUnit: number): NodePicture {
    const image = new OffscreenCanvas(
        Math.max(1, Math.ceil((n.width + 2 * PICTURE_MARGIN) * pixelsPerUnit)),
        Math.max(1, Math.ceil((n.height + 2 * PICTURE_MARGIN) * pixelsPerUnit)),
    );
    const was = ctx;
    ctx = image.getContext("2d")!;
    try {
        // the node alone, in the middle, as a view at that zoom would show it
        const offsetX = (n.width / 2 + PICTURE_MARGIN) * pixelsPerUnit;
        const offsetY = (n.height / 2 + PICTURE_MARGIN) * pixelsPerUnit;
        // as if not selected: a selected node is drawn brighter instead ([SpaceRenderer]), and selecting remakes no picture
        drawNode({ ...n, x: 0, y: 0, depthScale: 1, selected: false }, grid, { scale: pixelsPerUnit, offsetX, offsetY, nodes: [], edges: [], selectionBox: null, shells: [] }, 1, true);
    } finally {
        ctx = was;
    }
    return { image, pixelsPerUnit, margin: PICTURE_MARGIN };
}

/** What the picture of [n] depends on: when this changes, it is made anew. */
function pictureKey(n: SpaceSceneNode): string {
    return pictureKeyWith(n, paintingOf(n));
}

/**
 * The same for headsets: of a terminal that runs, not what it shows. That is told as it changes and drawn over the node
 * ([tellOfTerminal]); the node's picture, which is slow to send, is not made anew for it.
 */
function vrPictureKey(n: SpaceSceneNode): string {
    return n.type === "terminal" && shownTerminals?.has(n.id) ? pictureKeyWith(n, null) : pictureKey(n);
}

function pictureKeyWith(n: SpaceSceneNode, painting: Painting | null): string {
    const picture = pictureOf(n);
    return JSON.stringify([
        // not whether it is selected: its picture is the same ([pictureOfNode])
        n.lines, n.width, n.height, n.shape, n.bg, n.fg, n.fontSize, n.faint, n.manual, n.type, n.language, colours().background, colours().foreground,
        picture === null ? 0 : nameOf(picture), painting === null ? 0 : nameOf(painting), n.id === editing?.id && editing.editor.shown,
    ]);
}

/** Whether the rectangle between two corners, given in the graph's units, and widened by [margin], is at least partly in the view of [width] by [height]. */
function inView(scene: Scene, width: number, height: number, x1: number, y1: number, x2: number, y2: number, margin: number): boolean {
    const left = -scene.offsetX / scene.scale - margin;
    const top = -scene.offsetY / scene.scale - margin;
    const right = (width - scene.offsetX) / scene.scale + margin;
    const bottom = (height - scene.offsetY) / scene.scale + margin;
    return Math.max(x1, x2) >= left && Math.min(x1, x2) <= right && Math.max(y1, y2) >= top && Math.min(y1, y2) <= bottom;
}

/** The node whose text is being edited, and its editor. */
let editing: { id: string; editor: NodeEditor | GridEditor } | null = null;

/**
 * Which editor a node's text gets: neovim drawn by this page from
 * what it tells of its screen, or (`set node-editor=terminal` in
 * the init file) neovim in a terminal laid over the node.
 */
let useGridEditor = true;

/** Neovim's own line while a node is edited: the command line being typed, or the mode. In the place of the page's status line. */
const editorLine = document.getElementById("editor-line") as HTMLDivElement;

/** Whose lines are below the graph, told in the middle of them. */
const which = document.getElementById("which") as HTMLDivElement;

/** While a node is edited in neovim, its lines are shown and the page's status line is not. */
function showEditorLines(on: boolean) {
    editorLine.style.display = on ? "block" : "none";
    which.textContent = on ? "nvim" : "graph";
    if (!on) showEditorStatus(null);
}

function showEditorLine(text: string | null) {
    // never empty: it covers the page's status line as long as it is shown
    editorLine.textContent = text ?? " ";
}

/** Neovim's status line while a node is edited, in its colours; above its own line. */
const editorStatus = document.getElementById("editor-status") as HTMLDivElement;

function showEditorStatus(pieces: StatusPiece[] | null) {
    editorStatus.replaceChildren(...(pieces ?? []).map((piece) => {
        const span = document.createElement("span");
        span.textContent = piece.text;
        // without neovim's colour scheme the page's colours, as the rest of the page has them
        if (useNvimColours && piece.foreground !== undefined) span.style.color = piece.foreground;
        if (useNvimColours && piece.background !== undefined) span.style.background = piece.background;
        return span;
    }));
    const shown = pieces !== null && pieces.length > 0;
    editorStatus.style.display = shown ? "block" : "none";
    editorLine.classList.toggle("alone", !shown);
}

/** How many characters fit in the strips below the graph: one is measured. */
function stripColumns(): number {
    const probe = document.createElement("span");
    probe.textContent = "M".repeat(20);
    probe.style.visibility = "hidden";
    statusLine.appendChild(probe);
    const width = probe.getBoundingClientRect().width / 20;
    probe.remove();
    return width > 0 ? Math.max(20, Math.floor((statusLine.clientWidth - 12) / width)) : 120;
}

/** What was drawn last: where a click landed is judged against what the user saw. */
let lastScene: Scene | null = null;

/** Where the text of [n] is on the page, and the size of its character cells. */
function textPlace(n: SceneNode, whole: Scene, grid: client.GridMetrics): Place {
    const scene = viewOf(n, whole);
    const bounds = canvas.getBoundingClientRect();
    return {
        left: bounds.left + textCorner(n).left * scene.scale + scene.offsetX,
        top: bounds.top + textCorner(n).top * scene.scale + scene.offsetY,
        cellWidth: grid.advance * n.fontSize * scene.scale,
        cellHeight: grid.lineHeight * n.fontSize * scene.scale,
    };
}

/** Open neovim on the node [id]; with [click] (a position in the view) the cursor goes to the character clicked. */
function editNode(view: client.JsView, grid: client.GridMetrics, id: string, click: { x: number; y: number } | null) {
    // the click that asks for this also closed the editor that was open; that one tells its text first
    endEdit(view);
    // a terminal node has no text to edit: entering it is going to its shell
    if (lastScene?.nodes.find((it) => it.id === id)?.type === "terminal") enterTerminal(view, id);
    else (closing ?? Promise.resolve()).then(() => openEditor(view, grid, id, click));
}

// --- terminal nodes: a shell in a terminal over the node, started when the node is first entered, running on afterwards ---

/** The terminals of the nodes that were entered, by the view they are in and the node; and the one the keys go to, if any. */
const terminals = new Map<client.JsView, Map<string, TerminalNode>>();
let inTerminal: { view: client.JsView; id: string } | null = null;

/** The keys go to the shell of the node [id] from now on (Shift+Escape, or a click on the graph, gives them back); it is started if it is not running. */
function enterTerminal(view: client.JsView, id: string) {
    const ofView = terminals.get(view) ?? new Map<string, TerminalNode>();
    terminals.set(view, ofView);
    leaveTerminal();
    if (!ofView.has(id)) {
        ofView.set(id, new TerminalNode(document.body, () => {
            ofView.delete(id);
            if (inTerminal?.view === view && inTerminal.id === id) inTerminal = null;
            tellLive?.({ id, left: 0, top: 0, cellWidth: 0, cellHeight: 0, columns: 0, background: "#000000", rows: null });
            page.repaint();
        }, () => {
            page.repaint();
            tellOfTerminal(view, id);
        }));
    }
    inTerminal = { view, id };
    ofView.get(id)?.focus(true);
    page.repaint();
}

function leaveTerminal() {
    const was = inTerminal;
    inTerminal = null;
    if (was !== null) terminals.get(was.view)?.get(was.id)?.focus(false);
}

/** What a terminal is sent for a key that is not a character, by the key's name. */
const TERMINAL_KEYS: { [key: string]: string } = {
    Enter: "\r", Backspace: "\x7f", Tab: "\t", Escape: "\x1b", Delete: "\x1b[3~",
    ArrowUp: "\x1b[A", ArrowDown: "\x1b[B", ArrowRight: "\x1b[C", ArrowLeft: "\x1b[D",
    Home: "\x1b[H", End: "\x1b[F", PageUp: "\x1b[5~", PageDown: "\x1b[6~",
};

/**
 * A key pressed in the headset's page (opened on this machine: the server takes none from the network), as if it were
 * pressed in the window: in a terminal node it is the shell's, in the line the line's, otherwise whatever takes the keys now.
 */
function keyFromPage(key: string, ctrl: boolean, shift: boolean) {
    const terminal = inTerminal === null ? undefined : terminals.get(inTerminal.view)?.get(inTerminal.id);
    const character = [...key].length === 1;
    if (terminal !== undefined && key === "Escape" && shift) leaveTerminal();
    else if (terminal !== undefined && character) terminal.type(ctrl ? String.fromCharCode(key.toUpperCase().charCodeAt(0) & 31) : key);
    else if (terminal !== undefined) terminal.type(TERMINAL_KEYS[key] ?? "");
    else if (lineOpen() && character && !ctrl) {
        lineInput.value += key;
        lineInput.dispatchEvent(new Event("input"));
    } else {
        // pressed and let go at once: the headset says no more of it, and what lasts while a key is held must end
        (lineOpen() ? lineInput : window).dispatchEvent(new KeyboardEvent("keydown", { key, ctrlKey: ctrl, shiftKey: shift, bubbles: true }));
        window.dispatchEvent(new KeyboardEvent("keyup", { key, ctrlKey: ctrl, shiftKey: shift, bubbles: true }));
    }
}

/** The terminals of the view that is drawn: what their nodes show is asked of them ([paintingOf]). */
let shownTerminals: Map<string, TerminalNode> | undefined;

/**
 * Headsets are told what a terminal shows as it changes, and not with the pictures of the nodes, which come a few times a
 * second: [tellLive] sends it (null while nothing serves headsets), each terminal's at most every [LIVE_MS]; [liveWaiting]
 * are those that are about to be told of. The cells are as big as the window draws them ([liveGrid]), times what nodes are
 * shown at in headsets ([vrNodeScale]).
 */
let tellLive: ((live: VrLive) => void) | null = null;
const LIVE_MS = 16;
const liveWaiting = new Set<string>();
let liveGrid: client.GridMetrics | null = null;
let vrNodeScale = 1;

function tellOfTerminal(view: client.JsView, id: string) {
    if (tellLive !== null && !liveWaiting.has(id)) {
        liveWaiting.add(id);
        setTimeout(() => {
            liveWaiting.delete(id);
            const terminal = terminals.get(view)?.get(id);
            const n = terminals.get(view) === shownTerminals ? lastScene?.nodes.find((it) => it.id === id && it.type === "terminal") : undefined;
            const grid = liveGrid;
            if (terminal !== undefined && n !== undefined && grid !== null) {
                const painting = terminal.painting(inkOf(n), n.bg ?? colours().background);
                // cells that look alike, after each other, as one
                const runs = (cells: PaintedCell[]) => cells.reduce<[string, string, string, boolean][]>((done, cell) => {
                    const last = done[done.length - 1];
                    const bold = cell.bold === true;
                    if (last !== undefined && last[1] === cell.foreground && last[2] === cell.background && last[3] === bold) last[0] += cell.text;
                    else done.push([cell.text, cell.foreground, cell.background, bold]);
                    return done;
                }, []);
                tellLive?.({
                    id,
                    left: (-n.width / 2 + n.fontSize * 0.5) * vrNodeScale,
                    top: (-n.height / 2 + n.fontSize * 0.5) * vrNodeScale,
                    cellWidth: grid.advance * n.fontSize * vrNodeScale,
                    cellHeight: grid.lineHeight * n.fontSize * vrNodeScale,
                    columns: painting.rows[0]?.length ?? 0,
                    background: painting.background,
                    rows: painting.rows.map(runs),
                });
            }
        }, LIVE_MS);
    }
}

/**
 * Give the terminals of [view] as many rows and columns as their nodes have room for now ([scene]); one whose node is no more
 * is closed. Those of other views run on as they are.
 */
function sizeTerminals(view: client.JsView, scene: Scene, grid: client.GridMetrics) {
    if (inTerminal !== null && inTerminal.view !== view) leaveTerminal();
    shownTerminals = terminals.get(view);
    liveGrid = grid;
    [...(shownTerminals ?? [])].forEach(([id, terminal]) => {
        const n = scene.nodes.find((it) => it.id === id && it.type === "terminal");
        if (view.nodeText(id) == null) terminal.close();
        else if (n !== undefined) {
            // the room [textCorner] leaves on each side
            const inner = (side: number) => side - n.fontSize;
            terminal.size(inner(n.width) / (grid.advance * n.fontSize), inner(n.height) / (grid.lineHeight * n.fontSize));
        }
    });
}

function openEditor(view: client.JsView, grid: client.GridMetrics, id: string, click: { x: number; y: number } | null) {
    const n = lastScene?.nodes.find((it) => it.id === id);
    const text = view.textToEdit(id);
    if (n === undefined || lastScene === null || text == null || editing !== null) return;
    const place = textPlace(n, lastScene, grid);
    const bounds = canvas.getBoundingClientRect();
    const lines = text.split("\n");
    const row = click === null ? 0 : Math.min(lines.length - 1, Math.max(0, Math.floor((bounds.top + click.y - place.top) / place.cellHeight)));
    const column = click === null ? 0 : Math.min(Math.max(0, lines[row].length - 1), Math.max(0, Math.floor((bounds.left + click.x - place.left) / place.cellWidth)));
    view.textEditStarted(id);
    if (useGridEditor) {
        // drawn in the node, so in the node's colours whatever they are
        // a new node's text is replaced by what is typed: its editor is ready to type, any other starts in normal mode
        const insert = view.hasDefaultText(id);
        // a node whose text is kept in a file says which, and where in it, in neovim's status line where neovim names the
        // copy it edits (`node.txt`), in neovim's colours; neovim itself is left alone, its plugins too
        const home: string = require("os").homedir();
        const where = view.fileOf(id)?.replace(new RegExp(`^${home.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?=/)`), "~") ?? null;
        // by its full path (`%F`) or its name (`%f`, `%t`): the first that the line has
        let copy = NODE_FILE;
        const named = (pieces: StatusPiece[]) => {
            const name = [copy, NODE_FILE].find((it) => pieces.some((piece) => piece.text.includes(it))) ?? NODE_FILE;
            const at = where === null ? -1 : pieces.findIndex((piece) => piece.text.includes(name));
            return at < 0 ? pieces : pieces.map((piece, i) => (i === at ? { ...piece, text: piece.text.replace(name, where!) } : piece));
        };
        const editor = GridEditor.open(text, row, column, { background: n.bg ?? colours().background, foreground: inkOf(n) }, filetypeOf(n), insert, {
            text: (now) => view.textTyped(now),
            exit: () => endEdit(view),
            shown: () => page.repaint(),
            drawn: () => page.repaint(),
            line: showEditorLine,
            message: showMessage,
            status: (pieces) => showEditorStatus(named(pieces)),
        });
        copy = editor.file;
        editor.statusColumns = Math.max(MIN_STATUS_COLUMNS, stripColumns() - (where === null ? 0 : Math.max(0, where.length - NODE_FILE.length)));
        editing = { id, editor };
        if (editorLine.style.display !== "block") showEditorLine(null);
        showEditorLines(true);
    } else {
        const editor = NodeEditor.open(
            document.body,
            FONT,
            text,
            row,
            column,
            // a node in the palette's colours is edited in them
            n.palette && n.bg !== null && n.fg !== null ? { background: n.bg, foreground: n.fg } : null,
            (now) => view.textTyped(now),
            () => endEdit(view),
            () => page.repaint(),
        );
        editing = { id, editor };
        editor.place(place);
    }
}

/** Where the cells of the editor of [n] go on the canvas: the cells its text is drawn in, and all of the node to draw on. */
function cellsOf(n: SceneNode, whole: Scene, grid: client.GridMetrics): Cells {
    const place = textPlace(n, whole, grid);
    const scene = viewOf(n, whole);
    const bounds = canvas.getBoundingClientRect();
    const fontPx = n.fontSize * scene.scale;
    const nodeLeft = (n.x - n.width / 2) * scene.scale + scene.offsetX;
    const nodeTop = (n.y - n.height / 2) * scene.scale + scene.offsetY;
    // of a node that reaches beyond the view only what is seen: the editor starts at the view's edge and neovim scrolls the text in it
    const clipLeft = Math.max(nodeLeft, 0);
    const clipTop = Math.max(nodeTop, 0);
    return {
        left: Math.max(place.left - bounds.left, Math.min(place.left - bounds.left - nodeLeft, place.cellWidth)),
        top: Math.max(place.top - bounds.top, Math.min(place.top - bounds.top - nodeTop, place.cellHeight)),
        cellWidth: place.cellWidth,
        cellHeight: place.cellHeight,
        font: `${fontPx}px ${FONT}`,
        baseline: grid.ascent * fontPx,
        clip: {
            left: clipLeft,
            top: clipTop,
            width: Math.max(0, Math.min(nodeLeft + n.width * scene.scale, canvas.clientWidth) - clipLeft),
            height: Math.max(0, Math.min(nodeTop + n.height * scene.scale, canvas.clientHeight) - clipTop),
        },
    };
}

/** How many columns and rows of [cells] are seen. */
function roomOf(cells: Cells): { columns: number; rows: number } {
    return {
        columns: Math.floor((cells.clip.left + cells.clip.width - cells.left) / cells.cellWidth),
        rows: Math.floor((cells.clip.top + cells.clip.height - cells.top) / cells.cellHeight),
    };
}

/** The row and column of the edited node's text that the view position x, y is in; null outside the node or without a drawn editor. */
function editorCellAt(x: number, y: number, grid: client.GridMetrics): { row: number; column: number } | null {
    const n = lastScene?.nodes.find((it) => it.id === editing?.id);
    const cells = n !== undefined && lastScene !== null && editing?.editor instanceof GridEditor ? cellsOf(n, lastScene, grid) : null;
    const inside = cells !== null && x >= cells.clip.left && x < cells.clip.left + cells.clip.width && y >= cells.clip.top && y < cells.clip.top + cells.clip.height;
    return cells !== null && inside
        ? { row: Math.max(0, Math.floor((y - cells.top) / cells.cellHeight)), column: Math.max(0, Math.floor((x - cells.left) / cells.cellWidth)) }
        : null;
}

/** The row and column of the terminal [terminal] of the node [id] at the view position x, y; null outside it. */
function terminalCellAt(id: string, terminal: TerminalNode, x: number, y: number, grid: client.GridMetrics): { row: number; column: number } | null {
    const n = lastScene?.nodes.find((it) => it.id === id);
    const place = n !== undefined && lastScene !== null ? textPlace(n, lastScene, grid) : null;
    const bounds = canvas.getBoundingClientRect();
    const column = place === null ? -1 : Math.floor((bounds.left + x - place.left) / place.cellWidth);
    const row = place === null ? -1 : Math.floor((bounds.top + y - place.top) / place.cellHeight);
    return column >= 0 && column < terminal.columns && row >= 0 && row < terminal.rows ? { row, column } : null;
}

/**
 * What runs in a node and has the keys: neovim editing the node's text, or the shell of a terminal node. The mouse in its
 * node is its, from a press to the release, for the buttons it [takes]; the wheel is its wherever the pointer is, when it
 * has a [wheel]; Shift+Escape gives the keys back to the graph ([leave]).
 */
interface Inside {
    /** The row and column of what it shows at the view position x, y; null outside it. */
    cellAt(x: number, y: number): { row: number; column: number } | null;
    takes(button: number): boolean;
    mouse(action: "press" | "drag" | "release", button: number, cell: { row: number; column: number }, e: MouseEvent): void;
    wheel: ((direction: "up" | "down", cell: { row: number; column: number }) => void) | null;
    leave(): void;
}

/** What runs in a node of [view] and has the keys now, if anything does. */
function inside(view: client.JsView, grid: client.GridMetrics): Inside | null {
    const editor = editing?.editor;
    const shell = inTerminal?.view === view ? inTerminal : null;
    const terminal = shell === null ? undefined : terminals.get(view)?.get(shell.id);
    // the one in a terminal of its own takes neither the mouse nor the wheel: it is no grid of cells drawn in the node
    return editor instanceof GridEditor ? {
        cellAt: (x, y) => editorCellAt(x, y, grid),
        takes: (button) => button === 0,
        mouse: (action, _button, cell) => editor.mouse(action, cell.row, cell.column),
        wheel: (direction, cell) => editor.wheel(direction, cell.row, cell.column),
        leave: () => endEdit(view),
    } : editor !== undefined ? {
        cellAt: () => null,
        takes: () => false,
        mouse: () => {},
        wheel: null,
        leave: () => endEdit(view),
    } : terminal !== undefined && shell !== null ? {
        cellAt: (x, y) => terminalCellAt(shell.id, terminal, x, y, grid),
        takes: () => true,
        mouse: (action, button, cell, e) => terminal.mouse(action, button, cell.row, cell.column, { ctrl: e.ctrlKey, alt: e.altKey, shift: e.shiftKey }),
        wheel: (direction, cell) => terminal.wheel(direction, cell.row, cell.column),
        leave: leaveTerminal,
    } : null;
}

/** Close the editor, if any; what it holds becomes the node's text. */
function endEdit(view: client.JsView) {
    const was = editing;
    if (was !== null) {
        editing = null;
        showEditorLines(false);
        closing = was.editor.close().then((text) => {
            closing = null;
            view.textEditEnded(text);
        });
    }
}

/**
 * What a headset shows of [view]: its nodes where they are in the
 * graph, each with where its picture is in the atlas. The pictures
 * are those of the 3D drawing, and are made for the headset also
 * when this window draws the graph flat.
 */
function vrScene(view: client.JsView | null | undefined, grid: client.GridMetrics): VrScene | null {
    // on the walls of the room, where that is asked for and there is one (`:set walls`): then the nodes are where they are in the room
    const onWalls = view ? view.worldOnWalls(Float64Array.from(wallsOf(roomSurfaces ?? [])), Float64Array.from(standing)) as OnWallsScene | null : null;
    const world = onWalls ?? (view ? view.world() as SpaceScene<SpaceSceneNode> : null);
    const size = onWalls?.nodeScale ?? 1;
    vrNodeScale = size;
    if (world !== null) {
        // pictures of its own, finer than the window's: a headset shows a node from near
        vrAtlas ??= new SpaceRenderer(document.createElement("canvas"), () => {});
        vrAtlas.prepare(world.nodes, VR_PIXELS_PER_UNIT, vrPictureKey, (n, pixelsPerUnit) => pictureOfNode(n, grid, pixelsPerUnit), true);
    }
    const atlas = vrAtlas;
    const links: { [id: string]: string[] } = view ? view.links() : {};
    return world === null || atlas === null ? null : {
        nodes: world.nodes.map((n) => {
            const place = atlas.placeOf(n.id);
            const leads: string[] | undefined = links[n.id];
            const node = {
                id: n.id, x: n.x, y: n.y, z: n.z, width: n.width * size, height: n.height * size, fill: n.bg ?? colours().background, ink: inkOf(n),
                ...(leads === undefined ? {} : { links: leads.slice(0, MAX_VR_LINKS).map((it) => it.slice(0, MAX_VR_LINK)) }),            };
            // a node shown smaller: its picture has more pixels to a unit
            return place === null ? node : {
                ...node, page: place.page, px: place.x, py: place.y, pw: place.width, ph: place.height,
                pixelsPerUnit: place.pixelsPerUnit / size, middleX: place.middleX, middleY: place.middleY,
            };
        }),
        edges: world.edges.map((e) => [e.from, e.to]),
        pageSide: atlas.pageSide,
        background: canvasColour(colours().background),
        ink: colours().foreground,
        exploring: view!.exploring,
        status: statusText.slice(0, MAX_VR_STATUS),
        inRoom: onWalls !== null,
        ...(onWalls === null ? {} : { walls: wallsOf(roomSurfaces ?? []) }),
    };
}

/**
 * Where who wears the headset stands in the room and which way they look, as the headset last said: x and z of each, seen
 * from above. Until it says: at the room's origin, looking where the room's -z is.
 */
let standing: [number, number, number, number] = [0, 0, 0, -1];

/** [SpaceScene] as `worldOnWalls` gives it: the nodes where they are in the room, and how many times their size. */
type OnWallsScene = SpaceScene<SpaceSceneNode> & { nodeScale: number };

/** The status line as it was last shown, for a headset to show too; no more of it than this. */
let statusText = "";
const MAX_VR_STATUS = 200;
/** Of a node's links a headset is told this many, and this much of each. */
const MAX_VR_LINKS = 12;
const MAX_VR_LINK = 80;

/** The pictures of the nodes for headsets, and how many pixels a unit of the graph (in a headset: a millimetre) is in them. */
let vrAtlas: SpaceRenderer<SpaceSceneNode> | null = null;
const VR_PIXELS_PER_UNIT = 3;

/** The nodes of the graph as it was last drawn with depth. */
let lastSpaceIds: string[] = [];

/**
 * What `:cache` tells: for each kind of picture that is kept, how
 * much memory it takes and how many of the nodes of the graph shown
 * have one. [threeD]: the graph is drawn with depth now.
 */
/** The room a headset is in, as it last told of it, or the made-up one (`:room-mock`); null while there is neither. */
let roomSurfaces: VrSurface[] | null = null;
/**
 * Its outlines as lines in the graph ([outlines]), for the 3D drawing. Made when first drawn: the middle of the graph shown
 * then is where a graph is in the room, and stays so whatever is moved or shown afterwards.
 */
let roomLines: Float64Array | null = null;

function setRoom(surfaces: VrSurface[]) {
    roomSurfaces = surfaces;
    roomLines = null;
    page.repaint();
}

/** The middle of the box around [nodes]; of none, the graph's origin. */
function middleOf(nodes: SpaceSceneNode[]): [number, number, number] {
    const middle = (of: (n: SpaceSceneNode) => number) => nodes.length === 0 ? 0 : (Math.min(...nodes.map(of)) + Math.max(...nodes.map(of))) / 2;
    return [middle((n) => n.x), middle((n) => n.y), middle((n) => n.z)];
}

/** The room, for `:room`: a line for each surface with what it is, how big, and where its middle is (metres; y up from the floor). */
function roomText(): string {
    const metres = (n: number) => n.toFixed(2);
    const line = (s: VrSurface) => {
        const xs = s.polygon.map((p) => p[0]);
        const zs = s.polygon.map((p) => p[1]);
        const size = `${metres(Math.max(...xs) - Math.min(...xs))} × ${metres(Math.max(...zs) - Math.min(...zs))} m`;
        return `${s.label.padEnd(12)} ${size.padEnd(16)} at ${s.matrix.slice(12, 15).map(metres).join(", ")}`;
    };
    return roomSurfaces === null ? "No headset has told of its room (start with --vr, enter in the headset; its space must be set up). :room-mock makes one up."
        : roomSurfaces.length === 0 ? "The headset knows of no surfaces in its room."
        : roomSurfaces.map(line).join("\n");
}

function cacheStats(grid: client.GridMetrics, threeD: boolean): string {
    const megabytes = (bytes: number) => `${(bytes / 2 ** 20).toFixed(1)}MB`.padStart(8);
    const share = (count: number, of: number) => `${String(count).padStart(5)}/${of} nodes (${String(of === 0 ? 0 : Math.round(100 * count / of)).padStart(3)}%)`;
    const line = (name: string, bytes: number, count: number, of: number) => `${(name + ":").padEnd(20)}${megabytes(bytes)}, ${share(count, of)}`;
    const nodes = lastScene?.nodes ?? [];
    // a node's drawing is kept by what it shows, as [drawContent] names it
    const drawn = nodes.filter((n) => {
        const picture = pictureOf(n);
        const content = picture !== null ? pictureContent(n, picture) : textContent(n, grid, paintingOf(n));
        return bitmaps.has(`${content.key}\n${content.width} ${content.height}`);
    }).length;
    const flat = bitmaps.stats;
    const space = spaceRenderer?.stats(lastSpaceIds) ?? null;
    // 2D cache: the text of a node as 2D mode draws it. 3D closeup nodes: those seen at more than half their size, drawn at the
    // size they are seen at. 3D node cache: every node at its size at zoom 1, which is kept on the pages of the texture atlas,
    // here (RAM) and on the graphics card (VRAM). The 2D cache keeps at most 1 GB; the others have no limit.
    return [
        `drawing now: ${threeD ? "3d" : "2d"}`,
        "",
        line("2d cache", flat.bytes, drawn, nodes.length),
        "",
        ...(space === null ? ["3d: nothing yet"] : [
            line("3d closeup nodes", space.ownBytes, space.own, lastSpaceIds.length),
            line("3d node cache", space.usedBytes, space.atlas, lastSpaceIds.length),
            "3d texture atlas:",
            `    ${megabytes(space.pageBytes).trim()} RAM,`,
            `    ${megabytes(space.cardBytes).trim()} VRAM,`,
            `    ${space.pages} pages`,
        ]),
    ].join("\n");
}

/** The editor that is being closed has not told its text yet; no other is opened meanwhile. */
let closing: Promise<void> | null = null;

/** How many of the screen's pixels the canvas covers, as the page tells it; null until it has. */
let screenPixels: { width: number; height: number } | null = null;

function draw(view: client.JsView, grid: client.GridMetrics) {
    const dpr = window.devicePixelRatio;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    // One pixel of the canvas for each of the screen's, or the picture is resampled and blurred.
    // With a pixel ratio that is not whole, rounding does not always come to what the page was given.
    const pixelWidth = screenPixels?.width ?? Math.round(width * dpr);
    const pixelHeight = screenPixels?.height ?? Math.round(height * dpr);
    if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
        canvas.width = pixelWidth;
        canvas.height = pixelHeight;
    }
    view.resize(width, height);
    const dark = !isLight(colours().background);
    if (view.dark !== dark || pickerColours.childElementCount === 0) {
        view.dark = dark;
        fillPicker(view);
    }
    if (pickerOpen()) markPickedType(view);
    const scene = view.scene() as Scene;
    // In 3D mode the graph is drawn with depth on the canvas below, and this one, clear, has only what is laid over the graph:
    // the node being edited with its editor, and the selection box.
    const threeD = view.threeD;
    sizeTerminals(view, scene, grid);
    spaceCanvas.style.display = threeD ? "block" : "none";
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = canvasColour(colours().background);
    if (threeD) ctx.clearRect(0, 0, canvas.width, canvas.height);
    else ctx.fillRect(0, 0, canvas.width, canvas.height);
    // the lines below the graph are in its colours
    const style = document.documentElement.style;
    style.setProperty("--fg", colours().foreground);
    style.setProperty("--bg", colours().background);
    style.setProperty("--canvas", canvasColour(colours().background));
    ctx.setTransform(dpr * scene.scale, 0, 0, dpr * scene.scale, dpr * scene.offsetX, dpr * scene.offsetY);
    ctx.strokeStyle = colours().foreground;
    ctx.fillStyle = colours().foreground;
    ctx.lineWidth = 1;
    // a kept drawing shown smaller is averaged, not sampled
    ctx.imageSmoothingQuality = "high";
    if (threeD) {
        spaceRenderer ??= new SpaceRenderer(spaceCanvas, () => page.repaint());
        // for trying how fast it is without them
        spaceRenderer.edgesShown = process.env.SGE_NO_EDGES === undefined;
        const t0 = performance.now();
        const spaceNow = view.space() as SpaceScene<SpaceSceneNode>;
        lastSpaceIds = spaceNow.nodes.map((n) => n.id);
        if (roomSurfaces !== null) roomLines ??= outlines(roomSurfaces, middleOf((view.world() as SpaceScene<SpaceSceneNode>).nodes));
        spaceRenderer.room(roomLines === null ? null : view.seen(roomLines));
        const t1 = performance.now();
        const pending = spaceRenderer.draw(
            spaceNow, width, height, canvas.width, canvas.height, dpr, canvasColour(colours().background), colours().foreground, ARROWHEAD_RADIUS, EDGE_FADE,
            pictureKey, (n, pixelsPerUnit) => pictureOfNode(n, grid, pixelsPerUnit),
            // a selected node's outline stays visible however small it is
            (n, pixelsPerUnit) => ({ fill: n.bg ?? colours().background, ink: inkOf(n), outline: n.selected ? Math.max(2, 2.2 * dpr / pixelsPerUnit) : 1 }),
            (n) => n.selected,
            inkOf,
        );
        if (STAGE_TIMES !== undefined) {
            const s = spaceRenderer.spent;
            const line = `scene ${(t1 - t0).toFixed(1)} nodes ${s.nodes.toFixed(1)} edges ${s.edges.toFixed(1)} render ${s.render.toFixed(1)} made ${s.made} calls ${s.calls}\n`;
            require("fs").appendFileSync(STAGE_TIMES, line);
        }
        // the pictures that were not made for this frame
        if (pending) page.repaint();
        ctx.setTransform(dpr * scene.scale, 0, 0, dpr * scene.scale, dpr * scene.offsetX, dpr * scene.offsetY);
        ctx.strokeStyle = colours().foreground;
        ctx.fillStyle = colours().foreground;
    }
    // only what is in the view; a selected node's outline is the widest thing drawn around one
    const outline = Math.max(2, 2.2 / scene.scale);
    (threeD ? [] : scene.edges)
        .filter((e) => inView(scene, width, height, e.x1, e.y1, e.x2, e.y2, ARROWHEAD_RADIUS + 1))
        .forEach((e) => drawEdge(e, scene.scale, width, height));
    scene.nodes
        .filter((n) => !threeD || n.id === editing?.id)
        // the node being edited last: over the others
        .sort((a, b) => Number(a.id === editing?.id) - Number(b.id === editing?.id))
        .filter((n) => {
            const halfWidth = n.width * n.depthScale / 2;
            const halfHeight = n.height * n.depthScale / 2;
            return inView(scene, width, height, n.x - halfWidth, n.y - halfHeight, n.x + halfWidth, n.y + halfHeight, outline);
        })
        .forEach((n) => drawNode(n, grid, scene, dpr));
    // each node set its own
    ctx.setTransform(dpr * scene.scale, 0, 0, dpr * scene.scale, dpr * scene.offsetX, dpr * scene.offsetY);
    if (!threeD && scene.shells.length > 0) {
        ctx.globalAlpha = SHELL_ALPHA;
        ctx.lineWidth = 1 / scene.scale;
        const top = scene.shells.reduce((most, s) => Math.max(most, s.level), 0);
        const colourOf = shellColours(canvasColour(colours().background));
        scene.shells
            .filter((s) => inView(scene, width, height, s.x - s.radius, s.y - s.radius, s.x + s.radius, s.y + s.radius, 1))
            .forEach((s) => {
                const [r, g, b] = colourOf(s, top);
                ctx.strokeStyle = `rgb(${Math.round(r * 255)}, ${Math.round(g * 255)}, ${Math.round(b * 255)})`;
                ctx.beginPath();
                ctx.arc(s.x, s.y, s.radius, 0, 2 * Math.PI);
                ctx.stroke();
            });
        ctx.globalAlpha = 1;
    }
    lastScene = scene;
    const edited = scene.nodes.find((n) => n.id === editing?.id);
    const editor = editing?.editor;
    if (edited !== undefined && editor instanceof NodeEditor) editor.place(textPlace(edited, scene, grid));
    if (edited !== undefined && editor instanceof GridEditor && editor.shown) {
        // in page pixels, like the places it is given
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        const cells = cellsOf(edited, scene, grid);
        const room = roomOf(cells);
        editor.limit(room.columns, room.rows);
        editor.draw(flat, cells);
        ctx.setTransform(dpr * scene.scale, 0, 0, dpr * scene.scale, dpr * scene.offsetX, dpr * scene.offsetY);
    }
    const box = scene.selectionBox;
    if (box !== null) {
        ctx.strokeStyle = colours().foreground;
        ctx.lineWidth = 1 / scene.scale;
        ctx.setLineDash([4 / scene.scale, 4 / scene.scale]);
        ctx.strokeRect(Math.min(box.x1, box.x2), Math.min(box.y1, box.y2), Math.abs(box.x2 - box.x1), Math.abs(box.y2 - box.y1));
        ctx.setLineDash([]);
    }
}

function start(app: client.JsApp, grid: client.GridMetrics) {
    let scheduled = false;
    /** Do [action] with the view the page shows now; there is none before the first tab. */
    const current = (action: (view: client.JsView) => void) => {
        const view = app.current();
        if (view) action(view);
    };
    // For tests: each drawing is followed by a few more, and how long the last of them took from one frame to the next is noted.
    // (Not the time spent in here: the canvas only notes what to draw, and is drawn afterwards.)
    let again = 0;
    let before = 0;
    const paint = () => {
        scheduled = false;
        current((view) => {
            // nodes on their way move a step for each picture, so that they are seen going
            const moving = view.frame();
            draw(view, grid);
            if (moving) repaint();
        });
        if (DRAW_TIMES !== undefined) {
            const now = performance.now();
            if (again === 0) again = TIMED_FRAMES;
            else if (--again === 0) require("fs").appendFileSync(DRAW_TIMES, `${(now - before).toFixed(1)} ms at scale ${lastScene?.scale.toFixed(3)}\n`);
            before = now;
            if (again > 0) repaint();
        }
    };
    const repaint = () => {
        if (!scheduled) {
            scheduled = true;
            requestAnimationFrame(paint);
        }
    };
    page.repaint = repaint;
    page.editNode = (id, atClick, x, y) => current((view) => editNode(view, grid, id, atClick ? { x, y } : null));
    page.endEdit = () => current((view) => endEdit(view));
    page.tabs = () => showTabs(app);
    page.cacheStats = () => cacheStats(grid, app.current()?.threeD ?? false);
    page.room = roomText;
    page.roomMock = () => {
        setRoom(mockRoom());
        return roomText();
    };
    window.addEventListener("resize", repaint);
    // also when the screen's pixel density changes: the window moved to another screen, or the setting was changed
    new ResizeObserver((entries) => {
        const size = entries[0]?.devicePixelContentBoxSize?.[0];
        if (size !== undefined) screenPixels = { width: size.inlineSize, height: size.blockSize };
        repaint();
    }).observe(canvas, { box: "device-pixel-content-box" });

    // a neovim of the kind in use is kept started (the one started for the theme is of the grid's kind)
    if (useGridEditor) GridEditor.warm();
    else {
        GridEditor.discardSpare();
        NodeEditor.warm(document.body, FONT);
    }
    window.addEventListener("beforeunload", () => {
        GridEditor.endAll();
        NodeEditor.discardSpare();
        [...terminals.values()].forEach((ofView) => [...ofView.values()].forEach((it) => it.close()));
    });
    // Super and what is pressed with it are the window manager's: nothing here takes them, not the graph, neovim, a shell or the line
    window.addEventListener("keydown", (e) => {
        if (e.metaKey || e.key === "Meta" || e.key === "OS") e.stopImmediatePropagation();
    }, true);
    // before a terminal or neovim has the key: Shift+Escape gives the keys back to the graph, and is not theirs
    window.addEventListener("keydown", (e) => {
        if ((inTerminal !== null || editing !== null) && e.key === "Escape" && e.shiftKey) {
            e.stopPropagation();
            e.preventDefault();
            current((view) => inside(view, grid)?.leave());
        }
    }, true);

    // What the mouse and the keys mean is the client core's business; this only reports them.
    // Positions from clientX / clientY: offsetX / offsetY of an event made by a test come out divided by the device pixel ratio.
    const x = (e: MouseEvent) => e.clientX - canvas.getBoundingClientRect().left;
    const y = (e: MouseEvent) => e.clientY - canvas.getBoundingClientRect().top;
    canvas.addEventListener("wheel", (e) => {
        e.preventDefault();
        // while something runs in a node the wheel is its, wherever the pointer is: it scrolls the text, not the graph
        current((view) => {
            const at = inside(view, grid);
            const cell = at?.cellAt(x(e), y(e)) ?? { row: 0, column: 0 };
            if (at?.wheel) {
                if (e.deltaY !== 0) at.wheel(e.deltaY < 0 ? "up" : "down", cell);
            } else view.wheel(x(e), y(e), e.deltaY);
        });
    }, { passive: false });
    // A button pressed in the node of what runs in it is its (in neovim the left one places the cursor, a drag selects;
    // a terminal tells the program that asked for the mouse), from the press to the release.
    let held: { at: Inside; button: number } | null = null;
    const toInside = (action: "press" | "drag" | "release", e: PointerEvent) => {
        const cell = held?.at.cellAt(x(e), y(e));
        if (held !== null && cell !== null && cell !== undefined) held.at.mouse(action, held.button, cell, e);
    };
    canvas.addEventListener("pointerdown", (e) => {
        // a drag goes on when the pointer leaves the window; an event made by a test has no pointer to capture
        if (e.isTrusted) canvas.setPointerCapture(e.pointerId);
        current((view) => {
            const at = inside(view, grid);
            held = at !== null && at.takes(e.button) && at.cellAt(x(e), y(e)) !== null ? { at, button: e.button } : null;
            if (held !== null) toInside("press", e);
            else {
                // a press anywhere else gives the keys back to the graph
                leaveTerminal();
                view.pointerDown(x(e), y(e), e.button, e.ctrlKey, e.shiftKey);
            }
        });
    });
    // a press that went to what runs in the node does not take the focus from it: a terminal's keys are its hidden xterm's
    canvas.addEventListener("mousedown", (e) => {
        if (held !== null) e.preventDefault();
    });
    canvas.addEventListener("pointermove", (e) => {
        if (held !== null) toInside("drag", e);
        else current((view) => view.pointerMove(x(e), y(e), e.buttons !== 0, e.shiftKey));
    });
    canvas.addEventListener("pointerup", (e) => {
        if (held !== null) toInside("release", e);
        else current((view) => view.pointerUp(x(e), y(e), e.button, e.ctrlKey));
        held = null;
    });
    canvas.addEventListener("contextmenu", (e) => e.preventDefault());
    window.addEventListener("keydown", (e) => current((view) => {
        const dismissed = paneOpen() && !lineOpen() && (!paneIsKept || e.key === "Escape");
        if (dismissed) hideMessage();
        const editor = editing?.editor;
        if (dismissed && editing === null && DISMISSING_KEYS.includes(e.key)) e.preventDefault();
        else if (inTerminal !== null) {
            // in a terminal node the keys are its shell's: the terminal has them already
        }
        else if (pickerOpen() && e.key === "Escape") view.pickerDismissed();
        // while a node is edited the keys are neovim's: the terminal takes them itself, the drawn editor is handed them
        else if (editor instanceof GridEditor) {
            if (editor.key(e)) e.preventDefault();
        }
        // and while the line is open they are the line's
        else if (editing === null && !lineOpen() && view.keyDown(e.key, e.ctrlKey, e.shiftKey)) e.preventDefault();
    }));
    // letting go of a key ends what lasts while it is held (`o`)
    window.addEventListener("keyup", (e) => current((view) => view.keyUp(e.key)));
    wirePicker(current);
    lineInput.addEventListener("input", () => current((view) => view.lineTyped(lineInput.value)));
    lineInput.addEventListener("keydown", (e) => {
        // The line's key, and nobody else's: on its way up to the window, Enter would find the line closed and
        // what its command said in the pane, and dismiss that as a key pressed over a message.
        e.stopPropagation();
        current((view) => {
            if (view.lineKey(e.key, e.ctrlKey, e.shiftKey)) e.preventDefault();
        });
    });
    // the line keeps the keys for as long as it is open
    lineInput.addEventListener("blur", () => {
        if (lineOpen()) lineInput.focus();
    });
    // Closing the window (its button, the window manager's kill) is asking to quit: the tabs with unsaved changes have a say.
    // No question can be asked while the page unloads (Chromium answers it with no), so the closing waits: the tabs are asked
    // right after, and the page closes itself when they agree.
    window.addEventListener("beforeunload", (e) => {
        if (!leaving) {
            e.returnValue = false;
            setTimeout(() => app.quit());
        }
    });
    showTabs(app);
    repaint();

    // `--vr <port>`: the graph of the tab shown, for a headset's browser ([vrServer])
    const vr = query.get("vr");
    if (vr !== null) {
        const token: string = require("crypto").randomBytes(16).toString("hex");
        // `--vr-lan` too: on the local network, for a headset without a cable
        const lan = query.get("vrLan") !== null;
        const source = {
            scene: () => vrScene(app.current(), grid),
            pages: () => vrAtlas?.atlas ?? [],
            room: setRoom,
            // what a headset says is only that: a node that is not there is not clicked
            click: (id: string, button: 0 | 2) => {
                const view = app.current();
                view?.clickNode(id, button);
                // the trigger on a terminal node goes into it, as a click on the graph elsewhere leaves it: the keys are the window's
                leaveTerminal();
                if (view && button === 0 && lastScene?.nodes.find((it) => it.id === id)?.type === "terminal") enterTerminal(view, id);
            },
            move: (id: string, x: number, y: number, z: number) => app.current()?.dragNode(id, x, y, z),
            follow: (id: string, letter: string) => app.current()?.followLink(id, letter),
            // as the keys of the window: Ctrl+O back, Ctrl+I forward
            jump: (back: boolean) => app.current()?.keyDown(back ? "o" : "i", true, false),
            explore: () => app.current()?.toggleExplore(),
            stand: (x: number, z: number, ax: number, az: number) => {
                standing = [x, z, ax, az];
            },
            key: keyFromPage,
        };
        // what is typed in the headset: four digits, good for a few tries only. `--no-pin`: none is asked for, but only on this
        // machine: on the local network anyone could come in
        const asked = lan || query.get("vrNoPin") === null;
        const pin = asked ? String(require("crypto").randomInt(10000)).padStart(4, "0") : null;
        const give = pin === null ? "(no PIN is asked for)" : `and give the PIN ${pin}`;
        const kept = lan && query.get("vrNoPin") !== null ? "\n--no-pin is not for the local network: the PIN is asked for." : "";
        tellLive = startVr(Number(vr) || 0, token, pin, lan, source, (address, error) => {
            const port = address === null ? "" : new URL(address).port;
            const text = address === null
                ? `VR: could not start: ${error}`
                : lan
                    ? `VR: answering on the local network. With the headset on the same Wi-Fi, open in its browser\n    ${address}\n` +
                        `go on past its warning about the certificate (this machine made it for itself), ${give}${kept}`
                    : `VR: with the headset on its USB cable, run\n    adb reverse tcp:${port} tcp:${port}\n` +
                        `open in the headset's browser\n    ${address}\n${give}`;
            // where the application was started from, and in the window
            process.stdout.write(`sane-graph-edit: ${text}\n`);
            showMessage(`${text}\n(Escape puts this away)`, false, true);
        });
    }

    const play = query.get("play");
    if (play !== null) setTimeout(() => replay(JSON.parse(require("fs").readFileSync(play, "utf8")), grid), PLAY_DELAY_MS);

    const capture = query.get("capture");
    if (capture !== null) {
        setTimeout(() => {
            current((view) => draw(view, grid));
            const png = canvas.toDataURL("image/png").replace(/^data:image\/png;base64,/, "");
            require("fs").writeFileSync(capture, Buffer.from(png, "base64"));
            // a test ends here whatever is unsaved: no question to the user
            leaving = true;
            window.close();
        }, Number(query.get("captureAfter") ?? CAPTURE_DELAY_MS));
    }
}

/** A user's action for a test to replay (`--play <json file>`): a pointer event at x, y from the middle of the window, or a key. */
interface Played {
    /** `type`: [key] goes to the node's editor as typed text. `wait`: nothing for [ms] milliseconds. */
    type: "pointerdown" | "pointermove" | "pointerup" | "wheel" | "keydown" | "keyup" | "type" | "wait" | "click";
    /** `click`: the element of the page to click, as a CSS selector. */
    selector?: string;
    ms?: number;
    /** x, y are from the middle of the node whose text starts with this instead: a test need not know how the window was laid out. */
    node?: string;
    /** With [node]: the middle of the character in this row and column of its text (from 0), instead of x, y. */
    row?: number;
    column?: number;
    x?: number;
    y?: number;
    button?: number;
    buttons?: number;
    deltaY?: number;
    key?: string;
    ctrl?: boolean;
    shift?: boolean;
    meta?: boolean;
}

/** Dispatches [events] as the DOM events a user would cause, so that the listeners above are what handles them. */
async function replay(events: Played[], grid: client.GridMetrics) {
    for (const p of events) {
        // from the middle of the window, whatever size the window manager gave it
        const node = p.node === undefined ? undefined : lastScene?.nodes.find((n) => n.lines.join("\n").startsWith(p.node!));
        const bounds = canvas.getBoundingClientRect();
        const fromX = bounds.left + (node !== undefined && lastScene !== null ? node.x * lastScene.scale + lastScene.offsetX : canvas.clientWidth / 2);
        const fromY = bounds.top + (node !== undefined && lastScene !== null ? node.y * lastScene.scale + lastScene.offsetY : canvas.clientHeight / 2);
        const cell = node !== undefined && lastScene !== null && p.row !== undefined && p.column !== undefined ? textPlace(node, lastScene, grid) : null;
        const clientX = cell !== null ? cell.left + (p.column! + 0.5) * cell.cellWidth : fromX + (p.x ?? 0);
        const clientY = cell !== null ? cell.top + (p.row! + 0.5) * cell.cellHeight : fromY + (p.y ?? 0);
        const common = { clientX, clientY, button: p.button, buttons: p.buttons, ctrlKey: p.ctrl, shiftKey: p.shift, bubbles: true };
        if (p.type === "wait") await new Promise((resolve) => setTimeout(resolve, p.ms));
        else if (p.type === "click") document.querySelector<HTMLElement>(p.selector ?? "")?.click();
        else if (p.type === "type" && lineOpen()) {
            lineInput.value += p.key ?? "";
            lineInput.dispatchEvent(new Event("input"));
        } else if (p.type === "type" && editing?.editor instanceof GridEditor) {
            // the same text as for the terminal, where Enter and Escape are single characters
            const names: { [character: string]: string } = { "\r": "<CR>", "\u001b": "<Esc>", "<": "<lt>" };
            editing.editor.type([...(p.key ?? "")].map((c) => names[c] ?? c).join(""));
        } else if (p.type === "type") editing?.editor.type(p.key ?? "");
        else if (p.type === "keydown") {
            (lineOpen() ? lineInput : window).dispatchEvent(new KeyboardEvent("keydown", { key: p.key, ctrlKey: p.ctrl, shiftKey: p.shift, metaKey: p.meta, bubbles: true }));
        }
        else if (p.type === "keyup") window.dispatchEvent(new KeyboardEvent("keyup", { key: p.key, bubbles: true }));
        else if (p.type === "wheel") canvas.dispatchEvent(new WheelEvent("wheel", { ...common, deltaY: p.deltaY, cancelable: true }));
        else canvas.dispatchEvent(new PointerEvent(p.type, common));
    }
}

/** For tests (`SGE_DRAW_TIMES=<file>`): how long each drawing took is added to the file. */
const DRAW_TIMES = process.env.SGE_DRAW_TIMES;
/** For tests (`SGE_STAGE_TIMES=<file>`): how long the parts of each drawing in 3D mode took, in milliseconds. */
const STAGE_TIMES = process.env.SGE_STAGE_TIMES;
const TIMED_FRAMES = 6;

/** For tests: after the first drawing, so that positions in the window mean what the test saw. */
const PLAY_DELAY_MS = 800;

/** For tests (`--capture`): long enough for the first drawing; `--capture-after <ms>` for a test that changes something meanwhile. */
const CAPTURE_DELAY_MS = 1500;

/** What the client core asks of this page (`openView`); filled in once there is a view. */
const page: {
    repaint: () => void;
    editNode: (id: string, atClick: boolean, x: number, y: number) => void;
    endEdit: () => void;
    setting: (name: string, value: string) => void;
    showLine: (text: string) => void;
    hideLine: () => void;
    showMessage: (text: string, list?: boolean) => void;
    hideMessage: (list?: boolean) => void;
    showPicker: (x: number, y: number) => void;
    hidePicker: () => void;
    tabs: () => void;
    cacheStats: () => string;
    room: () => string;
    roomMock: () => string;
    confirm: (title: string, question: string) => boolean;
    choose: (kind: string, name: string) => string | null;
    exit: () => void;
    status: (text: string) => void;
} = {
    repaint: () => {},
    cacheStats: () => "",
    room: () => "",
    roomMock: () => "",
    editNode: () => {},
    endEdit: () => {},
    setting: (name, value) => {
        if (name === "nvim-theme") useNvimColours = value !== "off";
        if (name === "node-editor") useGridEditor = value !== "terminal";
        page.repaint();
    },
    showLine,
    hideLine,
    showMessage,
    hideMessage,
    showPicker,
    hidePicker,
    tabs: () => {},
    confirm: (title, question) => window.confirm(`${title}\n\n${question}`),
    choose: (kind, name) => require("electron").ipcRenderer.sendSync("choose", kind, name),
    exit: () => {
        leaving = true;
        window.close();
    },
    status: (text) => {
        // a space keeps the line's height
        const shown = text === "" ? " " : text;
        if (statusLine.textContent !== shown) statusLine.textContent = shown;
        statusText = text;
    },
};

const query = new URLSearchParams(window.location.search);
const problem = query.get("problem");
const url = query.get("url");
const file = query.get("file");
const grid = measureGrid();

/** How long the graphs wait for neovim's colour scheme at most; if it comes later, the page takes it then. */
const THEME_WAIT_MS = 3000;

/**
 * Neovim started and its colour scheme read before the graphs load, so that they are drawn in it from the first
 * picture; done when it is read, or when neovim fails or keeps them waiting longer than [THEME_WAIT_MS].
 */
function themed(): Promise<void> {
    GridEditor.warm();
    const theirs = GridEditor.colours().then((it) => {
        if (it !== null) {
            nvimColours = it;
            page.repaint();
        }
    });
    return Promise.race([theirs, new Promise<void>((resolve) => setTimeout(resolve, THEME_WAIT_MS))]);
}

/** This window runs the editor itself. */
function startAlone() {
    try {
        start(host.openLocalApp(file, grid, page), grid);
    } catch (e) {
        say(`cannot open ${file}: ${e instanceof Error ? e.message : e}`);
    }
}

if (problem !== null) {
    say(problem);
} else themed().then(() => {
    if (query.get("attach") !== null) {
        client.attachApp(file, grid, page, (app, error) => {
            if (app) start(app, grid);
            else say(`--attach: ${error}`);
        });
    } else if (url === null) {
        startAlone();
    } else {
        client.openApp(url, file, grid, page, (app, error) => {
            if (app) start(app, grid);
            // an address that was found, not asked for, may be what a window that is gone left behind
            else if (query.get("asked") === null) startAlone();
            else say(`cannot connect to the editor at ${url}: ${error}`);
        });
    }
});
