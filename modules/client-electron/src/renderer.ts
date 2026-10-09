import { client } from "sane-graph-edit-client-core";
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

/** Behind the graph: a little darker than a light node colour, a little lighter than a dark one. */
function canvasColour(nodes: string): string {
    const rgb = channels(nodes);
    const light = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2] > 127;
    const towards = light ? 0 : 255;
    return "#" + rgb.map((c) => Math.round(c + (towards - c) * CANVAS_CONTRAST).toString(16).padStart(2, "0")).join("");
}
const CORNER_RADIUS = 2;
/** Off while the editor is being worked on: a node without a colour of its own is not filled, so the editor's extent shows against it. */
const PAINT_PLAIN_NODES = false;
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

function fillPicker(view: client.JsView) {
    const offered = view.pickerStyles() as { columns: number; styles: { bg: string | null; fg: string | null; label: string }[] };
    pickerColours.style.gridTemplateColumns = `repeat(${offered.columns}, 1fr)`;
    offered.styles.forEach((style, index) => {
        const button = document.createElement("button");
        button.textContent = style.label;
        // a style without colours is the default one
        button.style.background = style.bg ?? "var(--bg)";
        button.style.color = style.fg ?? "var(--fg)";
        button.addEventListener("click", () => view.pickColour(index));
        pickerColours.appendChild(button);
    });
    picker.querySelectorAll<HTMLButtonElement>("button[data-size]").forEach((b) => b.addEventListener("click", () => view.pickSize(Number(b.dataset.size))));
    picker.querySelectorAll<HTMLButtonElement>("button[data-shape]").forEach((b) => b.addEventListener("click", () => view.pickShape(b.dataset.shape!)));
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

function drawNode(n: SceneNode, grid: client.GridMetrics, scale: number) {
    const left = n.x - n.width / 2;
    const top = n.y - n.height / 2;
    ctx.beginPath();
    if (n.shape === "oval") ctx.ellipse(n.x, n.y, n.width / 2, n.height / 2, 0, 0, 2 * Math.PI);
    else ctx.roundRect(left, top, n.width, n.height, CORNER_RADIUS);
    if (n.bg !== null || PAINT_PLAIN_NODES) {
        ctx.fillStyle = n.bg ?? colours().background;
        ctx.fill();
    }
    ctx.strokeStyle = n.fg ?? colours().foreground;
    // a selected node's outline stays visible however far out the view is zoomed
    ctx.lineWidth = n.selected ? Math.max(2, 2.2 / scale) : 1;
    ctx.stroke();
    // the node being edited has its editor where the text would be, once that shows
    if (n.fontSize * scale >= MIN_TEXT_PX && !(n.id === editing?.id && editing.editor.shown)) {
        ctx.fillStyle = n.fg ?? colours().foreground;
        ctx.font = `${n.fontSize}px ${FONT}`;
        const textLeft = n.x - n.textWidth / 2;
        const textTop = n.y - n.textHeight / 2;
        n.lines.forEach((line, i) => ctx.fillText(line, textLeft, textTop + (grid.ascent + i * grid.lineHeight) * n.fontSize));
    }
}

/** The node whose text is being edited, and its editor. */
let editing: { id: string; editor: NodeEditor } | null = null;

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
    const editor = NodeEditor.open(
        document.body,
        FONT,
        text,
        row,
        column,
        (now) => view.textTyped(now),
        () => endEdit(view),
        () => page.repaint(),
    );
    editing = { id, editor };
    editor.place(place);
}

/** Close the editor, if any; what it holds becomes the node's text. */
function endEdit(view: client.JsView) {
    const was = editing;
    if (was !== null) {
        editing = null;
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
    if (edited !== undefined) editing?.editor.place(textPlace(edited, scene, grid));
    const box = scene.selectionBox;
    if (box !== null) {
        ctx.strokeStyle = colours().foreground;
        ctx.lineWidth = 1 / scene.scale;
        ctx.setLineDash([4 / scene.scale, 4 / scene.scale]);
        ctx.strokeRect(Math.min(box.x1, box.x2), Math.min(box.y1, box.y2), Math.abs(box.x2 - box.x1), Math.abs(box.y2 - box.y1));
        ctx.setLineDash([]);
    }
}

function start(view: client.JsView, grid: client.GridMetrics) {
    let scheduled = false;
    let fitted = false;
    const paint = () => {
        scheduled = false;
        if (!fitted && canvas.clientWidth > 0) {
            fitted = true;
            view.resize(canvas.clientWidth, canvas.clientHeight);
            // the sizes of the nodes are known once the scene has been asked for
            view.scene();
            view.fit();
        }
        draw(view, grid);
    };
    const repaint = () => {
        if (!scheduled) {
            scheduled = true;
            requestAnimationFrame(paint);
        }
    };
    page.repaint = repaint;
    page.editNode = (id, atClick, x, y) => editNode(view, grid, id, atClick ? { x, y } : null);
    page.endEdit = () => endEdit(view);
    window.addEventListener("resize", repaint);

    NodeEditor.warm(document.body, FONT);
    window.addEventListener("beforeunload", () => NodeEditor.discardSpare());
    NodeEditor.colours().then((theirs) => {
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
        view.wheel(x(e), y(e), e.deltaY);
    }, { passive: false });
    canvas.addEventListener("pointerdown", (e) => {
        // a drag goes on when the pointer leaves the window; an event made by a test has no pointer to capture
        if (e.isTrusted) canvas.setPointerCapture(e.pointerId);
        view.pointerDown(x(e), y(e), e.button, e.ctrlKey, e.shiftKey);
    });
    canvas.addEventListener("pointermove", (e) => view.pointerMove(x(e), y(e), e.buttons !== 0, e.shiftKey));
    canvas.addEventListener("pointerup", (e) => view.pointerUp(x(e), y(e), e.button, e.ctrlKey));
    canvas.addEventListener("contextmenu", (e) => e.preventDefault());
    window.addEventListener("keydown", (e) => {
        const dismissed = paneOpen() && !lineOpen() && editing === null;
        if (dismissed) hideMessage();
        if (dismissed && DISMISSING_KEYS.includes(e.key)) e.preventDefault();
        else if (pickerOpen() && e.key === "Escape") view.pickerDismissed();
        // while a node is edited the keys are neovim's, and while the line is open they are the line's
        else if (editing === null && !lineOpen() && view.keyDown(e.key, e.ctrlKey, e.shiftKey)) e.preventDefault();
    });
    fillPicker(view);
    lineInput.addEventListener("input", () => view.lineTyped(lineInput.value));
    lineInput.addEventListener("keydown", (e) => {
        if (view.lineKey(e.key, e.ctrlKey, e.shiftKey)) e.preventDefault();
    });
    // the line keeps the keys for as long as it is open
    lineInput.addEventListener("blur", () => {
        if (lineOpen()) lineInput.focus();
    });
    document.title = `${view.file} — Sane Graph Edit`;
    repaint();

    const play = query.get("play");
    if (play !== null) setTimeout(() => replay(JSON.parse(require("fs").readFileSync(play, "utf8"))), PLAY_DELAY_MS);

    const capture = query.get("capture");
    if (capture !== null) {
        setTimeout(() => {
            draw(view, grid);
            const png = canvas.toDataURL("image/png").replace(/^data:image\/png;base64,/, "");
            require("fs").writeFileSync(capture, Buffer.from(png, "base64"));
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
    /** x, y are from the middle of the node with this text instead: a test need not know how the window was laid out. */
    node?: string;
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
async function replay(events: Played[]) {
    for (const p of events) {
        // from the middle of the window, whatever size the window manager gave it
        const node = lastScene?.nodes.find((n) => n.lines.join("\n") === p.node);
        const fromX = node !== undefined && lastScene !== null ? node.x * lastScene.scale + lastScene.offsetX : canvas.clientWidth / 2;
        const fromY = node !== undefined && lastScene !== null ? node.y * lastScene.scale + lastScene.offsetY : canvas.clientHeight / 2;
        const clientX = fromX + (p.x ?? 0);
        const clientY = fromY + (p.y ?? 0);
        const common = { clientX, clientY, button: p.button, buttons: p.buttons, ctrlKey: p.ctrl, shiftKey: p.shift, bubbles: true };
        if (p.type === "wait") await new Promise((resolve) => setTimeout(resolve, p.ms));
        else if (p.type === "click") document.querySelector<HTMLElement>(p.selector ?? "")?.click();
        else if (p.type === "type" && lineOpen()) {
            lineInput.value += p.key ?? "";
            lineInput.dispatchEvent(new Event("input"));
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
    status: (text: string) => void;
} = {
    repaint: () => {},
    editNode: () => {},
    endEdit: () => {},
    setting: (name, value) => {
        if (name === "nvim-theme") useNvimColours = value !== "off";
        page.repaint();
    },
    showLine,
    hideLine,
    showMessage,
    hideMessage,
    showPicker,
    hidePicker,
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
if (problem !== null || url === null || file === null) {
    say(problem ?? "no file given");
} else {
    const grid = measureGrid();
    client.openView(url, file, grid, page, (view, error) => {
        if (view) start(view, grid);
        else say(`cannot open ${file}: ${error}`);
    });
}
