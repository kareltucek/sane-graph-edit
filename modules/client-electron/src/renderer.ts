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
}

const FONT = "monospace";
const LINE_HEIGHT = 1.2;
const CORNER_RADIUS = 5;
const ARROWHEAD_RADIUS = 3;
const ZOOM_STEP = 1.1;
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

    // scroll: scale. left drag, shift + middle drag: pan. middle drag: orbit, once there is a third dimension.
    canvas.addEventListener("wheel", (e) => {
        e.preventDefault();
        view.zoomAt(e.offsetX, e.offsetY, e.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP);
    }, { passive: false });
    let panning = false;
    canvas.addEventListener("pointerdown", (e) => {
        panning = e.button === 0 || (e.button === 1 && e.shiftKey);
        if (panning) canvas.setPointerCapture(e.pointerId);
    });
    canvas.addEventListener("pointermove", (e) => {
        if (panning) view.pan(e.movementX, e.movementY);
    });
    canvas.addEventListener("pointerup", () => {
        panning = false;
    });
    document.title = `${view.file} — Sane Graph Edit`;
    repaint();

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
