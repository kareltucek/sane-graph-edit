import { client } from "sane-graph-edit-client-core";

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
const CORNER_RADIUS = 5;
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

/** The font's cell, as fractions of the font size; the client core sizes nodes with it. */
function measureGrid(): client.GridMetrics {
    const size = 100;
    ctx.font = `${size}px ${FONT}`;
    const m = ctx.measureText("M");
    const ascent = (LINE_HEIGHT * size - (m.fontBoundingBoxAscent + m.fontBoundingBoxDescent)) / 2 + m.fontBoundingBoxAscent;
    return new client.GridMetrics(m.width / size, LINE_HEIGHT, ascent / size);
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
    ctx.fillStyle = n.bg ?? "#ffffff";
    ctx.fill();
    ctx.strokeStyle = n.fg ?? "#000000";
    // a selected node's outline stays visible however far out the view is zoomed
    ctx.lineWidth = n.selected ? Math.max(2, 2.2 / scale) : 1;
    ctx.stroke();
    if (n.fontSize * scale >= MIN_TEXT_PX) {
        ctx.fillStyle = n.fg ?? "#000000";
        ctx.font = `${n.fontSize}px ${FONT}`;
        const textLeft = n.x - n.textWidth / 2;
        const textTop = n.y - n.textHeight / 2;
        n.lines.forEach((line, i) => ctx.fillText(line, textLeft, textTop + (grid.ascent + i * grid.lineHeight) * n.fontSize));
    }
}

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
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.setTransform(dpr * scene.scale, 0, 0, dpr * scene.scale, dpr * scene.offsetX, dpr * scene.offsetY);
    ctx.strokeStyle = "#000000";
    ctx.fillStyle = "#000000";
    ctx.lineWidth = 1;
    scene.edges.forEach((e) => drawEdge(e, scene.scale));
    scene.nodes.forEach((n) => drawNode(n, grid, scene.scale));
    const box = scene.selectionBox;
    if (box !== null) {
        ctx.strokeStyle = "#000000";
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
    onRepaint = repaint;
    window.addEventListener("resize", repaint);

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
        if (view.keyDown(e.key, e.ctrlKey, e.shiftKey)) e.preventDefault();
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
    type: "pointerdown" | "pointermove" | "pointerup" | "wheel" | "keydown";
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
function replay(events: Played[]) {
    events.forEach((p) => {
        // from the middle of the window, whatever size the window manager gave it
        const clientX = canvas.clientWidth / 2 + (p.x ?? 0);
        const clientY = canvas.clientHeight / 2 + (p.y ?? 0);
        const common = { clientX, clientY, button: p.button, buttons: p.buttons, ctrlKey: p.ctrl, shiftKey: p.shift, bubbles: true };
        if (p.type === "keydown") window.dispatchEvent(new KeyboardEvent("keydown", { key: p.key, ctrlKey: p.ctrl, shiftKey: p.shift }));
        else if (p.type === "wheel") canvas.dispatchEvent(new WheelEvent("wheel", { ...common, deltaY: p.deltaY, cancelable: true }));
        else canvas.dispatchEvent(new PointerEvent(p.type, common));
    });
}

/** For tests: after the first drawing, so that positions in the window mean what the test saw. */
const PLAY_DELAY_MS = 800;

/** For tests (`--capture`): long enough for the first drawing; `--capture-after <ms>` for a test that changes something meanwhile. */
const CAPTURE_DELAY_MS = 1500;

let onRepaint: () => void = () => {};

const query = new URLSearchParams(window.location.search);
const problem = query.get("problem");
const url = query.get("url");
const file = query.get("file");
if (problem !== null || url === null || file === null) {
    say(problem ?? "no file given");
} else {
    const grid = measureGrid();
    client.openView(url, file, grid, () => onRepaint(), (view, error) => {
        if (view) start(view, grid);
        else say(`cannot open ${file}: ${error}`);
    });
}
