import { client, host } from "sane-graph-edit-editor-host";
import { Bitmaps, Recent } from "./bitmaps";
import { Canvas2D, Cells, GridEditor, Painting, StatusPiece } from "./gridEditor";
import { MarkdownPictures, Picture, REFERENCE_FONT_PX } from "./markdown";
import { Colours, NodeEditor, PAD_COLUMNS, Place, TERMINAL_FONT_PX } from "./nodeEditor";

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
    type: "plain" | "code" | "markdown";
    language: string | null;
    fontSize: number;
    /** How many times its sizes the node is drawn, around x, y: other than 1 for a node nearer or further than the plane the view is of. */
    depthScale: number;
    selected: boolean;
}

interface SceneEdge {
    x1: number;
    y1: number;
    x2: number;
    y2: number;
}

interface Scene {
    scale: number;
    offsetX: number;
    offsetY: number;
    nodes: SceneNode[];
    edges: SceneEdge[];
    /** The selection rectangle being dragged: two opposite corners. */
    selectionBox: SceneEdge | null;
}

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
const ARROWHEAD_RADIUS = 3;
/** Text smaller than this on the screen is not drawn. */
const MIN_TEXT_PX = 3;

const canvas = document.getElementById("graph") as HTMLCanvasElement;
const ctx = canvas.getContext("2d")!;
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

function showMessage(text: string) {
    pane.textContent = text;
    pane.style.display = "block";
    pane.scrollTop = 0;
}

function hideMessage() {
    pane.style.display = "none";
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

function drawEdge(e: SceneEdge, scale: number) {
    ctx.beginPath();
    ctx.moveTo(e.x1, e.y1);
    ctx.lineTo(e.x2, e.y2);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(e.x2, e.y2, ARROWHEAD_RADIUS, 0, 2 * Math.PI);
    ctx.fill();
}

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
    draw: (to: Canvas2D) => void;
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

/** The text of [n] in its cells: as neovim painted it if [painting] is given, plain otherwise. A little room around it for what reaches out of a cell. */
function textContent(n: SceneNode, grid: client.GridMetrics, painting: Painting | null): Content {
    const room = n.fontSize * 0.25;
    const textLeft = n.x - n.textWidth / 2;
    const textTop = n.y - n.textHeight / 2;
    const box = { left: textLeft - room, top: textTop - room, width: n.textWidth + 2 * room, height: n.textHeight + 2 * room };
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
        ? { key: `painting ${nameOf(painting)} ${n.fontSize}`, ...box, draw: (to) => GridEditor.drawPainting(to, cells, painting) }
        : {
            key: ["text", n.fontSize, ink, ...n.lines].join("\n"),
            ...box,
            draw: (to) => {
                to.fillStyle = ink;
                to.font = cells.font;
                n.lines.forEach((line, i) => to.fillText(line, textLeft, textTop + cells.baseline + i * cells.cellHeight));
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
    const bitmap = needed <= finest * SAME_SCALE
        ? bitmaps.get(`${content.key}\n${content.width} ${content.height}`, content.width, content.height, finest / 2 ** halvings, (to) => {
            to.translate(-content.left, -content.top);
            content.draw(to);
        })
        : null;
    const kept = bitmap?.scale ?? finest;
    if (bitmap === null) content.draw(ctx);
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

function drawNode(n: SceneNode, grid: client.GridMetrics, scene: Scene, dpr: number) {
    const view = viewOf(n, scene);
    const scale = view.scale;
    ctx.setTransform(dpr * scale, 0, 0, dpr * scale, dpr * view.offsetX, dpr * view.offsetY);
    const left = n.x - n.width / 2;
    const top = n.y - n.height / 2;
    ctx.beginPath();
    if (n.shape === "oval") ctx.ellipse(n.x, n.y, n.width / 2, n.height / 2, 0, 0, 2 * Math.PI);
    else ctx.roundRect(left, top, n.width, n.height, CORNER_RADIUS);
    const ink = inkOf(n);
    ctx.fillStyle = n.bg ?? colours().background;
    ctx.fill();
    ctx.strokeStyle = ink;
    // a selected node's outline stays visible however far out the view is zoomed
    ctx.lineWidth = n.selected ? Math.max(2, 2.2 / scale) : 1;
    ctx.stroke();
    // the node being edited has its editor where the text would be, once that shows
    const shown = n.fontSize * scale >= MIN_TEXT_PX && !(n.id === editing?.id && editing.editor.shown);
    if (shown) {
        // a markdown node is its picture, once that is made; a code node what neovim painted, once that is
        const picture = pictureOf(n);
        drawContent(picture !== null ? pictureContent(n, picture) : textContent(n, grid, paintingOf(n)), view, dpr);
    }
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
        if (piece.foreground !== undefined) span.style.color = piece.foreground;
        if (piece.background !== undefined) span.style.background = piece.background;
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
        left: bounds.left + (n.x - n.textWidth / 2) * scene.scale + scene.offsetX,
        top: bounds.top + (n.y - n.textHeight / 2) * scene.scale + scene.offsetY,
        cellWidth: grid.advance * n.fontSize * scene.scale,
        cellHeight: grid.lineHeight * n.fontSize * scene.scale,
    };
}

/** Open neovim on the node [id]; with [click] (a position in the view) the cursor goes to the character clicked. */
function editNode(view: client.JsView, grid: client.GridMetrics, id: string, click: { x: number; y: number } | null) {
    // the click that asks for this also closed the editor that was open; that one tells its text first
    endEdit(view);
    (closing ?? Promise.resolve()).then(() => openEditor(view, grid, id, click));
}

function openEditor(view: client.JsView, grid: client.GridMetrics, id: string, click: { x: number; y: number } | null) {
    const n = lastScene?.nodes.find((it) => it.id === id);
    const text = view.nodeText(id);
    if (n === undefined || lastScene === null || text == null || editing !== null) return;
    const place = textPlace(n, lastScene, grid);
    const bounds = canvas.getBoundingClientRect();
    const lines = text.split("\n");
    const row = click === null ? 0 : Math.min(lines.length - 1, Math.max(0, Math.floor((bounds.top + click.y - place.top) / place.cellHeight)));
    const column = click === null ? 0 : Math.min(Math.max(0, lines[row].length - 1), Math.max(0, Math.floor((bounds.left + click.x - place.left) / place.cellWidth)));
    view.textEditStarted(id);
    if (useGridEditor) {
        // drawn in the node, so in the node's colours whatever they are
        const editor = GridEditor.open(text, row, column, { background: n.bg ?? colours().background, foreground: inkOf(n) }, filetypeOf(n), {
            text: (now) => view.textTyped(now),
            exit: () => endEdit(view),
            shown: () => page.repaint(),
            drawn: () => page.repaint(),
            line: showEditorLine,
            message: showMessage,
            status: showEditorStatus,
        });
        editor.statusColumns = stripColumns();
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
    const scene = view.scene() as Scene;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = canvasColour(colours().background);
    ctx.fillRect(0, 0, canvas.width, canvas.height);
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
    bitmaps.nextFrame();
    // only what is in the view; a selected node's outline is the widest thing drawn around one
    const outline = Math.max(2, 2.2 / scene.scale);
    scene.edges.filter((e) => inView(scene, width, height, e.x1, e.y1, e.x2, e.y2, ARROWHEAD_RADIUS + 1)).forEach((e) => drawEdge(e, scene.scale));
    scene.nodes
        .filter((n) => {
            const halfWidth = n.width * n.depthScale / 2;
            const halfHeight = n.height * n.depthScale / 2;
            return inView(scene, width, height, n.x - halfWidth, n.y - halfHeight, n.x + halfWidth, n.y + halfHeight, outline);
        })
        .forEach((n) => drawNode(n, grid, scene, dpr));
    // each node set its own
    ctx.setTransform(dpr * scene.scale, 0, 0, dpr * scene.scale, dpr * scene.offsetX, dpr * scene.offsetY);
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
        editor.draw(ctx, cells);
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
        current((view) => draw(view, grid));
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
    window.addEventListener("resize", repaint);
    // also when the screen's pixel density changes: the window moved to another screen, or the setting was changed
    new ResizeObserver((entries) => {
        const size = entries[0]?.devicePixelContentBoxSize?.[0];
        if (size !== undefined) screenPixels = { width: size.inlineSize, height: size.blockSize };
        repaint();
    }).observe(canvas, { box: "device-pixel-content-box" });

    // a neovim of the kind in use is kept started; its colour scheme is the page's theme
    if (useGridEditor) GridEditor.warm();
    else NodeEditor.warm(document.body, FONT);
    window.addEventListener("beforeunload", () => {
        GridEditor.endAll();
        NodeEditor.discardSpare();
    });
    (useGridEditor ? GridEditor.colours() : NodeEditor.colours()).then((theirs) => {
        if (theirs !== null) {
            nvimColours = theirs;
            repaint();
        }
    });

    // What the mouse and the keys mean is the client core's business; this only reports them.
    // Positions from clientX / clientY: offsetX / offsetY of an event made by a test come out divided by the device pixel ratio.
    const x = (e: MouseEvent) => e.clientX - canvas.getBoundingClientRect().left;
    const y = (e: MouseEvent) => e.clientY - canvas.getBoundingClientRect().top;
    canvas.addEventListener("wheel", (e) => {
        e.preventDefault();
        // while a node is edited the wheel is neovim's, wherever the pointer is: it scrolls the text, not the graph
        if (editing?.editor instanceof GridEditor) {
            const cell = editorCellAt(x(e), y(e), grid) ?? { row: 0, column: 0 };
            if (e.deltaY !== 0) editing.editor.wheel(e.deltaY < 0 ? "up" : "down", cell.row, cell.column);
        } else current((view) => view.wheel(x(e), y(e), e.deltaY));
    }, { passive: false });
    // The left button inside the node being edited is neovim's (it places the cursor, a drag selects), from the press to the release.
    let inEditor = false;
    const toEditor = (action: "press" | "drag" | "release", e: PointerEvent) => {
        const cell = editorCellAt(x(e), y(e), grid);
        if (cell !== null && editing?.editor instanceof GridEditor) editing.editor.mouse(action, cell.row, cell.column);
    };
    canvas.addEventListener("pointerdown", (e) => {
        // a drag goes on when the pointer leaves the window; an event made by a test has no pointer to capture
        if (e.isTrusted) canvas.setPointerCapture(e.pointerId);
        inEditor = e.button === 0 && editorCellAt(x(e), y(e), grid) !== null;
        if (inEditor) toEditor("press", e);
        else current((view) => view.pointerDown(x(e), y(e), e.button, e.ctrlKey, e.shiftKey));
    });
    canvas.addEventListener("pointermove", (e) => {
        if (inEditor) toEditor("drag", e);
        else current((view) => view.pointerMove(x(e), y(e), e.buttons !== 0, e.shiftKey));
    });
    canvas.addEventListener("pointerup", (e) => {
        if (inEditor) toEditor("release", e);
        else current((view) => view.pointerUp(x(e), y(e), e.button, e.ctrlKey));
        inEditor = false;
    });
    canvas.addEventListener("contextmenu", (e) => e.preventDefault());
    window.addEventListener("keydown", (e) => current((view) => {
        const dismissed = paneOpen() && !lineOpen();
        if (dismissed) hideMessage();
        const editor = editing?.editor;
        if (dismissed && editing === null && DISMISSING_KEYS.includes(e.key)) e.preventDefault();
        else if (pickerOpen() && e.key === "Escape") view.pickerDismissed();
        // while a node is edited the keys are neovim's: the terminal takes them itself, the drawn editor is handed them
        else if (editor instanceof GridEditor) {
            if (editor.key(e)) e.preventDefault();
        }
        // and while the line is open they are the line's
        else if (editing === null && !lineOpen() && view.keyDown(e.key, e.ctrlKey, e.shiftKey)) e.preventDefault();
    }));
    wirePicker(current);
    lineInput.addEventListener("input", () => current((view) => view.lineTyped(lineInput.value)));
    lineInput.addEventListener("keydown", (e) => current((view) => {
        if (view.lineKey(e.key, e.ctrlKey, e.shiftKey)) e.preventDefault();
    }));
    // the line keeps the keys for as long as it is open
    lineInput.addEventListener("blur", () => {
        if (lineOpen()) lineInput.focus();
    });
    // closing the window is asking to quit: the tabs with unsaved changes have a say
    window.addEventListener("beforeunload", (e) => {
        if (!leaving && !app.quit()) e.returnValue = false;
    });
    showTabs(app);
    repaint();

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
    type: "pointerdown" | "pointermove" | "pointerup" | "wheel" | "keydown" | "type" | "wait" | "click";
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
            (lineOpen() ? lineInput : window).dispatchEvent(new KeyboardEvent("keydown", { key: p.key, ctrlKey: p.ctrl, shiftKey: p.shift, bubbles: false }));
        }
        else if (p.type === "wheel") canvas.dispatchEvent(new WheelEvent("wheel", { ...common, deltaY: p.deltaY, cancelable: true }));
        else canvas.dispatchEvent(new PointerEvent(p.type, common));
    }
}

/** For tests (`SGE_DRAW_TIMES=<file>`): how long each drawing took is added to the file. */
const DRAW_TIMES = process.env.SGE_DRAW_TIMES;
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
    showMessage: (text: string) => void;
    hideMessage: () => void;
    showPicker: (x: number, y: number) => void;
    hidePicker: () => void;
    tabs: () => void;
    confirm: (title: string, question: string) => boolean;
    choose: (kind: string, name: string) => string | null;
    exit: () => void;
    status: (text: string) => void;
} = {
    repaint: () => {},
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
    },
};

const query = new URLSearchParams(window.location.search);
const problem = query.get("problem");
const url = query.get("url");
const file = query.get("file");
const grid = measureGrid();

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
} else if (query.get("attach") !== null) {
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
