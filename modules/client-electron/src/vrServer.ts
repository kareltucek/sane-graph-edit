// The graph for a headset: a small server in the window's page that a headset's browser asks for a page (vr.html) and then
// listens to, over a WebSocket, for what to show. The headset is a thin renderer: it holds nothing, works nothing out about the
// graph, and gets the nodes as the pictures this page has made of them anyway (the atlas of the 3D drawing) with where each is.
//
// Off unless asked for (`--vr <port>`). It answers on this machine only (127.0.0.1) and only to who knows the token in the
// address: the headset reaches it through its USB cable (`adb reverse`), which also makes the address `localhost` there,
// as a browser demands of a page that wants to be immersive. Nothing is opened to the network.
//
// Only when that is asked for too (`--vr-lan`) does it answer on the local network instead, for a headset on the same Wi-Fi
// without a cable: on the address of one network interface, over TLS (a page that is not from `localhost` must be, to be
// immersive) with a certificate it makes for itself, which the headset's browser warns about once. The token is still needed.

import type { IncomingMessage, Server, ServerResponse } from "http";
import type { WebSocket } from "ws";

/** A node for the headset: where it is in the graph (y down, z away) and where its picture is in the atlas; no `page` while it has none. */
export interface VrNode {
    id: string;
    x: number;
    y: number;
    z: number;
    width: number;
    height: number;
    fill: string;
    ink: string;
    /** The links the node has: each as its letter and, after a space, where it leads. */
    links?: string[];
    page?: number;
    /** The picture's rectangle on the page, in pixels, how many pixels a unit of the node is in it, and where in it the node's middle is. */
    px?: number;
    py?: number;
    pw?: number;
    ph?: number;
    pixelsPerUnit?: number;
    middleX?: number;
    middleY?: number;
}

/** What the headset shows. Edges are pairs of indices into [nodes]. */
export interface VrScene {
    nodes: VrNode[];
    edges: [number, number][];
    pageSide: number;
    background: string;
    ink: string;
    /** The view is in explore mode. */
    exploring: boolean;
    /** The window's status line. */
    status: string;
    /** The nodes are where they are in the room (the graph's units, y down from the floor): the graph is not put before the eyes. */
    inRoom: boolean;
    /** With [inRoom]: the walls as seen from above, of each the two ends, x and z of each (metres). Drawn where no room is seen: out of a headset. */
    walls?: number[];
}

/**
 * What a node that changes fast (a terminal) shows now, told apart from the scene and its pictures, which are too slow for
 * it: cells of text in rows, which the headset draws over the node. Where the cells are is from the node's middle, in the
 * graph's units (y down): the corner of the first one and the size of one. Each row is runs of cells that look alike: the
 * text (a character a cell), its colour and the colour behind it, and whether it is bold. No [rows]: the node shows this no
 * longer.
 */
export interface VrLive {
    id: string;
    left: number;
    top: number;
    cellWidth: number;
    cellHeight: number;
    columns: number;
    background: string;
    rows: [string, string, string, boolean][][] | null;
}

/**
 * A flat surface of the room the headset is in, as the headset reports it: what it is called (`wall`, `floor`, `desk`…; its
 * orientation where the headset gives no name), where it is in the room (a 4×4 matrix by columns, metres, y up, the floor at 0:
 * the surface lies in its x/z plane, y is its normal) and its outline there as pairs of x and z.
 */
export interface VrSurface {
    label: string;
    orientation: "horizontal" | "vertical";
    matrix: number[];
    polygon: [number, number][];
}

const MAX_SURFACES = 256;
const MAX_CORNERS = 64;
const MAX_LABEL = 32;
/** A headset sends little: the surfaces of its room. */
const MAX_MESSAGE_BYTES = 256 * 1024;

/** The surfaces in what a headset sent, or null when that is something else: only numbers and short names are taken from it. */
function surfacesOf(text: string): VrSurface[] | null {
    const numbers = (it: unknown, count: number) => Array.isArray(it) && it.length === count && it.every((n) => typeof n === "number" && Number.isFinite(n));
    try {
        const sent = JSON.parse(text);
        const fine = sent?.type === "room" && Array.isArray(sent.surfaces) && sent.surfaces.length <= MAX_SURFACES && sent.surfaces.every((s: any) =>
            typeof s?.label === "string" && numbers(s.matrix, 16) && Array.isArray(s.polygon) && s.polygon.length <= MAX_CORNERS &&
            s.polygon.every((p: unknown) => numbers(p, 2)));
        return !fine ? null : sent.surfaces.map((s: any) => ({
            label: s.label.slice(0, MAX_LABEL),
            orientation: s.orientation === "vertical" ? "vertical" : "horizontal",
            matrix: s.matrix,
            polygon: s.polygon,
        }));
    } catch {
        return null;
    }
}

/** A node's id is short; a longer one is not looked for. */
const MAX_ID = 64;

/** What a headset did with a controller: a button clicked on a node (0 the left one, 2 the right one), or explore mode asked into or out of. */
type VrDone = { type: "click"; id: string; button: 0 | 2 } | { type: "explore" } | { type: "walls" } |
    { type: "move"; id: string; x: number; y: number; z: number; held: boolean } |
    { type: "follow"; id: string; letter: string } | { type: "jump"; back: boolean } | { type: "stand"; x: number; z: number; ax: number; az: number } |
    { type: "key"; key: string; ctrl: boolean; shift: boolean } | { type: "pin"; pin: string };

/** A PIN given is no longer than this; the right one is four digits. */
const MAX_PIN = 16;

/** A key's name is no longer than this (`ArrowRight`, `F12`…). */
const MAX_KEY = 24;

/** Nobody stands further from the room's origin than this, metres. */
const MAX_STAND = 1000;

/** A node is not put further from the graph's origin than this, in its units. */
const MAX_PLACE = 1e7;

/** That, in what a headset sent, or null when it is something else. */
function doneOf(text: string): VrDone | null {
    try {
        const sent = JSON.parse(text);
        const click = sent?.type === "click" && typeof sent.id === "string" && sent.id.length <= MAX_ID && (sent.button === 0 || sent.button === 2);
        const place = (n: unknown) => typeof n === "number" && Number.isFinite(n) && Math.abs(n) <= MAX_PLACE;
        const move = sent?.type === "move" && typeof sent.id === "string" && sent.id.length <= MAX_ID && place(sent.x) && place(sent.y) && place(sent.z) &&
            (sent.held === undefined || typeof sent.held === "boolean");
        const follow = sent?.type === "follow" && typeof sent.id === "string" && sent.id.length <= MAX_ID && typeof sent.letter === "string" && /^[A-Za-z]$/.test(sent.letter);
        const metres = (n: unknown) => typeof n === "number" && Number.isFinite(n) && Math.abs(n) <= MAX_STAND;
        const stand = sent?.type === "stand" && metres(sent.x) && metres(sent.z) && metres(sent.ax) && metres(sent.az);
        const key = sent?.type === "key" && typeof sent.key === "string" && sent.key.length >= 1 && sent.key.length <= MAX_KEY &&
            typeof sent.ctrl === "boolean" && typeof sent.shift === "boolean";
        const pin = sent?.type === "pin" && typeof sent.pin === "string" && sent.pin.length <= MAX_PIN;
        return key ? { type: "key", key: sent.key, ctrl: sent.ctrl, shift: sent.shift }
            : pin ? { type: "pin", pin: sent.pin }
            : stand ? { type: "stand", x: sent.x, z: sent.z, ax: sent.ax, az: sent.az }
            : sent?.type === "jump" && typeof sent.back === "boolean" ? { type: "jump", back: sent.back }
            : follow ? { type: "follow", id: sent.id, letter: sent.letter }
            : click ? { type: "click", id: sent.id, button: sent.button }
            : move ? { type: "move", id: sent.id, x: sent.x, y: sent.y, z: sent.z, held: sent.held === true }
            : sent?.type === "explore" ? { type: "explore" }
            : sent?.type === "walls" ? { type: "walls" } : null;
    } catch {
        return null;
    }
}

/** What is asked of the page that has the graph. */
export interface VrSource {
    /** A button was clicked on the node [id] in a headset (0 the left one, 2 the right one); the id is whatever the headset said. */
    click: (id: string, button: 0 | 2) => void;
    /** The node [id] was taken in a headset and put at x, y, z of the graph; [held]: it is still held there, not let go of yet. */
    move: (id: string, x: number, y: number, z: number, held: boolean) => void;
    /** A headset asks to follow the link [letter] of the node [id]. */
    follow: (id: string, letter: string) => void;
    /** A headset asks to go back to where a link was followed from, or forward again. */
    jump: (back: boolean) => void;
    /** A headset asks into explore mode, or out of it. */
    explore: () => void;
    /** A headset asks for the graph on the walls of the room, or off them into its space (`:set walls`). */
    walls: () => void;
    /** Where who wears a headset stands in the room and which way they look, seen from above (metres; x and z); the last to say so is believed. */
    stand: (x: number, z: number, ax: number, az: number) => void;
    /** Whether the trigger on the node [id] goes into its terminal, which starts a shell: a headset on the local network may only with the PIN. */
    enters: (id: string) => boolean;
    /** A key was pressed in the headset's page ([key] as a DOM event names it): it is the window's, as if pressed there. Only asked on this machine. */
    key: (key: string, ctrl: boolean, shift: boolean) => void;
    /** A headset told of the room it is in: the last to do so is believed. */
    room: (surfaces: VrSurface[]) => void;
    /** What to show now; null when there is nothing. */
    scene: () => VrScene | null;
    /** The pages of the atlas, each with a number that goes up when it changes. */
    pages: () => { image: OffscreenCanvas; version: number }[];
}

/** A headset that has this much still to take is not told of a node that changes fast until it has caught up. */
const LIVE_BEHIND_BYTES = 64 * 1024;

/** How often what the headsets show is compared with what there is. */
const TICK_MS = 250;

/**
 * The files a headset's browser may ask for, by the path it asks
 * with. The page's script is asked for without the token (the page
 * names it plainly) and is given so: it is the same for everybody
 * and tells nothing of any graph. What it then connects with, to be
 * sent the graph, needs the token.
 */
const FILES: { [path: string]: { file: string; type: string; open?: boolean } } = {
    "/": { file: "vr.html", type: "text/html; charset=utf-8" },
    "/vr.js": { file: "vr.js", type: "text/javascript; charset=utf-8", open: true },
};

/**
 * The address of this machine on the local network: of the first
 * interface that has one of the private kind (192.168…, 10…,
 * 172.16–31…). Null when there is none.
 */
function lanAddress(): string | null {
    const os: typeof import("os") = require("os");
    const all = Object.values(os.networkInterfaces()).flatMap((it) => it ?? []).filter((it) => it.family === "IPv4" && !it.internal).map((it) => it.address);
    return all.find((it) => /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(it)) ?? null;
}

/**
 * A key and a certificate for TLS, made once with `openssl` and kept
 * with the application's configuration (the key readable by the user
 * only); made anew when missing or past its year. Nobody vouches for
 * the certificate: a browser warns before it goes on.
 */
function certificate(): { key: Buffer; cert: Buffer } {
    const fs: typeof import("fs") = require("fs");
    const path: typeof import("path") = require("path");
    const os: typeof import("os") = require("os");
    const { execFileSync }: typeof import("child_process") = require("child_process");
    const directory = path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config"), "sane-graph-edit");
    const key = path.join(directory, "vr-key.pem");
    const cert = path.join(directory, "vr-cert.pem");
    const valid = () => {
        try {
            execFileSync("openssl", ["x509", "-checkend", "86400", "-noout", "-in", cert], { stdio: "ignore" });
            return fs.existsSync(key);
        } catch {
            return false;
        }
    };
    if (!valid()) {
        fs.mkdirSync(directory, { recursive: true });
        execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", cert, "-days", "365", "-subj", "/CN=sane-graph-edit"], { stdio: "ignore" });
        fs.chmodSync(key, 0o600);
    }
    return { key: fs.readFileSync(key), cert: fs.readFileSync(cert) };
}

/** How many times in all a wrong PIN may be given. */
const PIN_TRIES = 5;

/**
 * Start answering at [port] (0: any free one) for headsets that know
 * [token], which one that is given the [pin] is told (not [atStart]:
 * anyone who asks): on this machine only, or with [lan] on the local
 * network, over TLS. There a headset that was not asked for the [pin]
 * at the start is asked for it when it would go into a terminal node,
 * and goes in only once it has given it. [told] gets the
 * address to open in the headset once the server listens, or what
 * went wrong. What is given back tells the headsets at once what a
 * node that changes fast shows ([VrLive]), and that the editor is
 * closing ([exit]).
 */
export function startVr(
    port: number, token: string, pin: string, atStart: boolean, lan: boolean, source: VrSource,
    told: (address: string | null, error: string | null) => void,
): VrServed {
    // loaded here, not bundled: Node's own, and a module of Node's kind
    const http: typeof import("http") = require("http");
    const https: typeof import("https") = require("https");
    const fs: typeof import("fs") = require("fs");
    const path: typeof import("path") = require("path");
    const crypto: typeof import("crypto") = require("crypto");
    const { WebSocketServer }: typeof import("ws") = require("ws");

    /** Whether who asks knows the token: it is in the address, as `?token=`. Compared in a time that tells nothing of how much of it was right. */
    const knows = (request: IncomingMessage) => {
        const given = Buffer.from(new URL(request.url ?? "/", "http://localhost").searchParams.get("token") ?? "");
        const wanted = Buffer.from(token);
        return given.length === wanted.length && crypto.timingSafeEqual(given, wanted);
    };

    /**
     * The PIN is what a person types in the headset; who gives it is sent on to the address with the token. It is short, so
     * it may be got wrong only [PIN_TRIES] times in all: after that no PIN is taken until the application is started again.
     */
    let triesLeft = PIN_TRIES;
    const pinOf = (request: IncomingMessage) => new URL(request.url ?? "/", "http://localhost").searchParams.get("pin");
    const pinRight = (given: string) => {
        const a = Buffer.from(given);
        const b = Buffer.from(pin);
        const right = triesLeft > 0 && a.length === b.length && crypto.timingSafeEqual(a, b);
        if (!right && triesLeft > 0) triesLeft -= 1;
        return right;
    };
    const pinPage = (wrong: boolean) =>
        `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>sane-graph-edit</title>` +
        `<body style="font: 28px sans-serif; background: #222; color: #ddd; text-align: center; padding-top: 3em">` +
        (triesLeft > 0
            ? `<form method="get" action="/">PIN <input name="pin" inputmode="numeric" maxlength="4" size="4" autofocus style="font: inherit"> ` +
                `<button style="font: inherit">Open</button></form>` + (wrong ? `<p>Wrong PIN; ${triesLeft} more tries.</p>` : "")
            : `<p>Too many wrong PINs. Start the application again.</p>`);

    // what is given back when no headset is served after all: tells nobody
    const unserved: VrServed = { live: () => {}, exit: () => {} };
    const host = lan ? lanAddress() : "127.0.0.1";
    if (host === null) {
        told(null, "this machine has no address on a local network");
        return unserved;
    }
    let secured: { key: Buffer; cert: Buffer } | null = null;
    try {
        secured = lan ? certificate() : null;
    } catch (error) {
        told(null, `could not make a certificate (is openssl there?): ${error}`);
        return unserved;
    }

    const answer = (request: IncomingMessage, response: ServerResponse) => {
        const asked = FILES[new URL(request.url ?? "/", "http://localhost").pathname];
        if (request.method !== "GET" || asked === undefined) {
            response.writeHead(404).end();
        } else if (!asked.open && !knows(request)) {
            const given = pinOf(request);
            // no PIN is asked for (`--no-pin`): whoever asks is told the token
            if (!atStart || (given !== null && pinRight(given))) response.writeHead(302, { Location: `/?token=${token}`, "Cache-Control": "no-store" }).end();
            else response.writeHead(given === null ? 200 : 403, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" }).end(pinPage(given !== null));
        } else {
            // only these two files, by name: nothing of what is asked is made into a path
            response.writeHead(200, { "Content-Type": asked.type, "Cache-Control": "no-store" });
            response.end(fs.readFileSync(path.join(__dirname, asked.file)));
        }
    };
    const server: Server = secured === null ? http.createServer(answer) : https.createServer(secured, answer);

    /**
     * Each headset, with what it was last sent: the scene as text, and the version of each page; whether it may go into
     * terminal nodes (it gave the PIN, or that is not asked for), and the click into one that waits for the PIN.
     */
    const headsets = new Map<WebSocket, { scene: string; pages: number[]; sending: boolean; trusted: boolean; waiting: string | null }>();
    /** What the nodes that change fast show now ([VrLive]), as it was last sent, by node. */
    const lives = new Map<string, string>();
    const sockets = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });
    // a page of a browser says where it is from: only the page this serves is taken, not another site's that got hold of the token
    const ownPage = (request: IncomingMessage) => {
        const from = request.headers.origin;
        return from === undefined || (URL.canParse(from) && new URL(from).host === request.headers.host);
    };
    server.on("upgrade", (request, socket, head) => {
        if (knows(request) && ownPage(request)) sockets.handleUpgrade(request, socket, head, (ws) => {
            const sent = { scene: "", pages: [] as number[], sending: false, trusted: atStart || !lan, waiting: null as string | null };
            headsets.set(ws, sent);
            lives.forEach((text) => ws.send(text));
            ws.on("close", () => headsets.delete(ws));
            ws.on("error", () => headsets.delete(ws));
            ws.on("message", (data, binary) => {
                const text = binary ? "" : data.toString();
                const surfaces = surfacesOf(text);
                const done = surfaces === null ? doneOf(text) : null;
                if (surfaces !== null) source.room(surfaces);
                // the trigger on a terminal node goes into it: on the local network only with the PIN, which is asked for then
                else if (done?.type === "click" && done.button === 0 && !sent.trusted && source.enters(done.id)) {
                    sent.waiting = done.id;
                    ws.send(JSON.stringify({ type: "pin" }));
                } else if (done?.type === "click") source.click(done.id, done.button);
                else if (done?.type === "pin" && !sent.trusted) {
                    sent.trusted = pinRight(done.pin);
                    ws.send(JSON.stringify({ type: "pinned", right: sent.trusted, left: triesLeft }));
                    // given wrong, the click still waits: the headset asks again
                    if (sent.trusted && sent.waiting !== null) source.click(sent.waiting, 0);
                    if (sent.trusted) sent.waiting = null;
                }
                else if (done?.type === "move") source.move(done.id, done.x, done.y, done.z, done.held);
                else if (done?.type === "follow") source.follow(done.id, done.letter);
                else if (done?.type === "jump") source.jump(done.back);
                else if (done?.type === "explore") source.explore();
                else if (done?.type === "walls") source.walls();
                else if (done?.type === "stand") source.stand(done.x, done.z, done.ax, done.az);
                // keys can do all the editor can: taken from this machine only, never from the local network
                else if (done?.type === "key" && !lan) source.key(done.key, done.ctrl, done.shift);
            });
        });
        else socket.destroy();
    });

    setInterval(() => {
        if (headsets.size > 0) {
            const scene = source.scene();
            const text = scene === null ? "" : JSON.stringify({ type: "scene", ...scene });
            const pages = source.pages();
            headsets.forEach((sent, ws) => {
                // one thing at a time to each: a page is big, and a slow headset is not sent the next before it has this one
                if (!sent.sending && ws.bufferedAmount === 0) {
                    const page = pages.findIndex((it, i) => it.version !== sent.pages[i]);
                    if (page >= 0) {
                        sent.sending = true;
                        const version = pages[page].version;
                        // the number of the page, then its picture
                        pages[page].image.convertToBlob({ type: "image/png" }).then((blob) => blob.arrayBuffer()).then((picture) => {
                            const message = new Uint8Array(4 + picture.byteLength);
                            new DataView(message.buffer).setUint32(0, page);
                            message.set(new Uint8Array(picture), 4);
                            if (ws.readyState === ws.OPEN) ws.send(message);
                            sent.pages[page] = version;
                        }).finally(() => {
                            sent.sending = false;
                        });
                    } else if (text !== sent.scene && text !== "") {
                        ws.send(text);
                        sent.scene = text;
                    }
                }
            });
        }
    }, TICK_MS);

    server.on("error", (error: Error) => told(null, error.message));
    // what a node that changes fast shows now ([VrLive]): at once, not at the next tick, and not to a headset that still has something to take
    const live = (now: VrLive) => {
        const text = JSON.stringify({ type: "live", ...now });
        // kept for a headset that comes later; of a node that shows it no longer, nothing is
        if (now.rows === null) lives.delete(now.id);
        else lives.set(now.id, text);
        // a headset that is behind is not told: it is told of the next change, which has all of this one
        headsets.forEach((_, ws) => {
            if (ws.readyState === ws.OPEN && ws.bufferedAmount <= LIVE_BEHIND_BYTES) ws.send(text);
        });
    };
    // this machine only, or the one address of it on the local network: never all of its interfaces
    server.listen(port, host, () => {
        const at = server.address();
        const where = lan ? `https://${host}` : "http://localhost";
        told(typeof at === "object" && at !== null ? `${where}:${at.port}` : null, null);
    });
    // the editor closes: the headsets leave their immersive sessions, and do not come back to what is gone
    const exit = () => headsets.forEach((_, ws) => {
        if (ws.readyState === ws.OPEN) ws.send(JSON.stringify({ type: "exit" }));
    });
    return { live, exit };
}

/** What [startVr] gives back: tell the headsets what a node that changes fast shows, and that the editor is closing. */
export interface VrServed {
    live: (now: VrLive) => void;
    exit: () => void;
}
