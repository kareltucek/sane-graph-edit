import { client, host } from "sane-graph-edit-editor-host";
import { Cells, GridEditor, StatusPiece } from "./gridEditor";
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
    fontSize: number;
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

/** The colour of the text and outline of [n]. A node with a background of its own and no text colour: the theme's text may not show on it. */
function inkOf(n: SceneNode): string {
    return n.fg ?? (n.bg === null ? colours().foreground : isLight(n.bg) ? "#000000" : "#ffffff");
}

function drawNode(n: SceneNode, grid: client.GridMetrics, scale: number) {
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
    if (n.fontSize * scale >= MIN_TEXT_PX && !(n.id === editing?.id && editing.editor.shown)) {
        ctx.fillStyle = ink;
        ctx.font = `${n.fontSize}px ${FONT}`;
        const textLeft = n.x - n.textWidth / 2;
        const textTop = n.y - n.textHeight / 2;
        n.lines.forEach((line, i) => ctx.fillText(line, textLeft, textTop + (grid.ascent + i * grid.lineHeight) * n.fontSize));
    }
}

/** The node whose text is being edited, and its editor. */
let editing: { id: string; editor: NodeEditor | GridEditor } | null = null;

/**
 * Which editor a node's text gets: neovim drawn by this page from
 * what it tells of its screen, or (`set node-editor=terminal` in
 * the init file) neovim in a terminal laid over the node.
 */
let useGridEditor = true;

/** Neovim's own line while a node is edited: the command line being typed, or the mode. Above the status line. */
const editorLine = document.getElementById("editor-line") as HTMLDivElement;

function showEditorLine(text: string | null) {
    editorLine.textContent = text ?? "";
    editorLine.style.display = text === null ? "none" : "block";
}

/** Neovim's status line while a node is edited, in its colours; above its own line and the page's status line. */
const editorStatus = document.getElementById("editor-status") as HTMLDivElement;

function showEditorStatus(pieces: StatusPiece[] | null) {
    editorStatus.replaceChildren(...(pieces ?? []).map((piece) => {
        const span = document.createElement("span");
        span.textContent = piece.text;
        if (piece.foreground !== undefined) span.style.color = piece.foreground;
        if (piece.background !== undefined) span.style.background = piece.background;
        return span;
    }));
    editorStatus.style.display = pieces === null || pieces.length === 0 ? "none" : "block";
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
function textPlace(n: SceneNode, scene: Scene, grid: client.GridMetrics): Place {
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
        const editor = GridEditor.open(text, row, column, { background: n.bg ?? colours().background, foreground: inkOf(n) }, {
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
function cellsOf(n: SceneNode, scene: Scene, grid: client.GridMetrics): Cells {
    const place = textPlace(n, scene, grid);
    const bounds = canvas.getBoundingClientRect();
    const fontPx = n.fontSize * scene.scale;
    return {
        left: place.left - bounds.left,
        top: place.top - bounds.top,
        cellWidth: place.cellWidth,
        cellHeight: place.cellHeight,
        font: `${fontPx}px ${FONT}`,
        baseline: grid.ascent * fontPx,
        clip: {
            left: (n.x - n.width / 2) * scene.scale + scene.offsetX,
            top: (n.y - n.height / 2) * scene.scale + scene.offsetY,
            width: n.width * scene.scale,
            height: n.height * scene.scale,
        },
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
        showEditorStatus(null);
        closing = was.editor.close().then((text) => {
            closing = null;
            view.textEditEnded(text);
        });
    }
}

/** The editor that is being closed has not told its text yet; no other is opened meanwhile. */
let closing: Promise<void> | null = null;

function draw(view: client.JsView, grid: client.GridMetrics) {
    const dpr = window.devicePixelRatio;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (canvas.width !== Math.round(width * dpr) || canvas.height !== Math.round(height * dpr)) {
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
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
    scene.edges.forEach((e) => drawEdge(e, scene.scale));
    scene.nodes.forEach((n) => drawNode(n, grid, scene.scale));
    lastScene = scene;
    const edited = scene.nodes.find((n) => n.id === editing?.id);
    const editor = editing?.editor;
    if (edited !== undefined && editor instanceof NodeEditor) editor.place(textPlace(edited, scene, grid));
    if (edited !== undefined && editor instanceof GridEditor && editor.shown) {
        // in page pixels, like the places it is given
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        editor.draw(ctx, cellsOf(edited, scene, grid));
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
    const paint = () => {
        scheduled = false;
        current((view) => draw(view, grid));
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

    // a neovim of the kind in use is kept started; its colour scheme is the page's theme
    if (useGridEditor) GridEditor.warm();
    else NodeEditor.warm(document.body, FONT);
    window.addEventListener("beforeunload", () => {
        GridEditor.discardSpare();
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
        current((view) => view.wheel(x(e), y(e), e.deltaY));
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
        const fromX = node !== undefined && lastScene !== null ? node.x * lastScene.scale + lastScene.offsetX : canvas.clientWidth / 2;
        const fromY = node !== undefined && lastScene !== null ? node.y * lastScene.scale + lastScene.offsetY : canvas.clientHeight / 2;
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
