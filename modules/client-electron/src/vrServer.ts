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
}

/** What is asked of the page that has the graph. */
export interface VrSource {
    /** What to show now; null when there is nothing. */
    scene: () => VrScene | null;
    /** The pages of the atlas, each with a number that goes up when it changes. */
    pages: () => { image: OffscreenCanvas; version: number }[];
}

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

/**
 * Start answering at [port] (0: any free one) for headsets that know
 * [token]: on this machine only, or with [lan] on the local network,
 * over TLS. [told] gets the address to open in the headset once the
 * server listens, or what went wrong.
 */
export function startVr(port: number, token: string, lan: boolean, source: VrSource, told: (address: string | null, error: string | null) => void) {
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

    const host = lan ? lanAddress() : "127.0.0.1";
    if (host === null) {
        told(null, "this machine has no address on a local network");
        return;
    }
    let secured: { key: Buffer; cert: Buffer } | null = null;
    try {
        secured = lan ? certificate() : null;
    } catch (error) {
        told(null, `could not make a certificate (is openssl there?): ${error}`);
        return;
    }

    const answer = (request: IncomingMessage, response: ServerResponse) => {
        const asked = FILES[new URL(request.url ?? "/", "http://localhost").pathname];
        if (request.method !== "GET" || asked === undefined) {
            response.writeHead(404).end();
        } else if (!asked.open && !knows(request)) {
            response.writeHead(403).end();
        } else {
            // only these two files, by name: nothing of what is asked is made into a path
            response.writeHead(200, { "Content-Type": asked.type, "Cache-Control": "no-store" });
            response.end(fs.readFileSync(path.join(__dirname, asked.file)));
        }
    };
    const server: Server = secured === null ? http.createServer(answer) : https.createServer(secured, answer);

    /** Each headset, with what it was last sent: the scene as text, and the version of each page. */
    const headsets = new Map<WebSocket, { scene: string; pages: number[]; sending: boolean }>();
    const sockets = new WebSocketServer({ noServer: true });
    server.on("upgrade", (request, socket, head) => {
        if (knows(request)) sockets.handleUpgrade(request, socket, head, (ws) => {
            headsets.set(ws, { scene: "", pages: [], sending: false });
            ws.on("close", () => headsets.delete(ws));
            ws.on("error", () => headsets.delete(ws));
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
    // this machine only, or the one address of it on the local network: never all of its interfaces
    server.listen(port, host, () => {
        const at = server.address();
        const where = lan ? `https://${host}` : "http://localhost";
        told(typeof at === "object" && at !== null ? `${where}:${at.port}/?token=${token}` : null, null);
    });
}
