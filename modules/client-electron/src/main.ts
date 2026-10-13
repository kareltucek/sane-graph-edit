import { app, BrowserWindow, dialog, ipcMain } from "electron";
import { createHash } from "crypto";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";

// sane-graph-edit-electron [--attach | --url <ws address>] [<file or directory>]
//
// Shows <file>, or the directory it is started in. The window runs
// an editor of its own, and serves the session socket of the
// directory if nobody does (for `ctl` and windows started with
// --attach). With --attach it joins the session of the window
// already running in the directory instead, as the Swing client's
// --attach does. It is also a client, not an editor, when --url
// names a WebSocket address, or when a window started with --ws
// has left one for this directory in a file beside its socket.

// For tests: --capture <png> saves the picture of the graph a moment after it was first drawn, and quits.
// --shots <directory> draws the window off the screen and saves a picture of all of it, the node's editor included, several times a second.

const SHOT_INTERVAL_MS = 250;

// An error nobody caught is written where the application was started from; Electron's own way is a dialog on the screen.
process.on("uncaughtException", (error) => console.error("sane-graph-edit: uncaught in the main process:", error));
process.on("unhandledRejection", (reason) => console.error("sane-graph-edit: unhandled rejection in the main process:", reason));

interface Args {
    url?: string;
    /** The address was given with --url, not found: if nobody answers there, that is an error. */
    asked?: boolean;
    /** Join the session of the window already running in this directory, through its socket. */
    attach?: boolean;
    file?: string;
    capture?: string;
    captureAfter?: string;
    play?: string;
    shots?: string;
    problem?: string;
    /** `--vr <port>`: also show the graph to a headset's browser that asks at this port of this machine (0: any free one). */
    vr?: string;
    /** `--vr-lan`: with `--vr`, answer on the local network (over TLS) instead of on this machine only. */
    vrLan?: boolean;
    /** `--no-pin`: with `--vr` on this machine only, no PIN is asked for. For trying things; on the local network it is still asked for. */
    vrNoPin?: boolean;
}

function parseArgs(argv: string[]): Args {
    const args: Args = {};
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a === "--url") args.url = argv[++i];
        else if (a === "--attach") args.attach = true;
        else if (a === "--capture") args.capture = path.resolve(argv[++i]);
        else if (a === "--capture-after") args.captureAfter = argv[++i];
        else if (a === "--play") args.play = path.resolve(argv[++i]);
        else if (a === "--shots") args.shots = path.resolve(argv[++i]);
        else if (a === "--vr") args.vr = argv[++i];
        else if (a === "--vr-lan") args.vrLan = true;
        else if (a === "--no-pin") args.vrNoPin = true;
        else if (a.startsWith("-")) continue; // Chromium's own switches
        else if (path.resolve(a) === app.getAppPath()) continue; // `electron <this application>`
        else if (args.file === undefined) args.file = path.resolve(a);
    }
    return args;
}

/** Where the window of [workspace] keeps its WebSocket address: as `client.SessionSocket` names the socket, with `.ws`. */
function addressFile(workspace: string): string {
    const runtime = process.env.XDG_RUNTIME_DIR || path.join(os.tmpdir(), `runtime-${os.userInfo().username}`);
    const hash = createHash("sha1").update(path.resolve(workspace)).digest("hex").slice(0, 16);
    return path.join(runtime, "sane-graph-edit", `${hash}.ws`);
}

/** With the address of the window that has the session of this directory, if one says it has; without one the page runs an editor of its own. */
function resolve(args: Args): Args {
    if (args.attach) return args;
    if (args.url !== undefined) return { ...args, asked: true };
    try {
        return { ...args, url: fs.readFileSync(addressFile(process.cwd()), "utf8").trim() };
    } catch {
        return args;
    }
}

app.whenReady().then(() => {
    const args = resolve(parseArgs(process.argv.slice(1)));
    const window = new BrowserWindow({
        width: 1000,
        height: 700,
        // the page is this application's own code and loads nothing from elsewhere; it uses Node for files and neovim
        // not throttled in the background: the page only draws when asked to, and a hidden window must still follow the session
        show: args.shots === undefined,
        webPreferences: { nodeIntegration: true, contextIsolation: false, backgroundThrottling: false, offscreen: args.shots !== undefined },
    });
    const shots = args.shots;
    if (shots !== undefined) {
        fs.mkdirSync(shots, { recursive: true });
        const started = Date.now();
        const timer = setInterval(() => {
            window.webContents.capturePage().then((image) => fs.writeFileSync(path.join(shots, `${Date.now() - started}.png`), image.toPNG()));
        }, SHOT_INTERVAL_MS);
        // a closed window has no page to take a picture of
        window.on("closed", () => clearInterval(timer));
    }
    window.setMenuBarVisibility(false);
    const query: Record<string, string> = {};
    if (args.url !== undefined) query.url = args.url;
    if (args.asked) query.asked = "1";
    if (args.attach) query.attach = "1";
    if (args.file !== undefined) query.file = args.file;
    if (args.problem !== undefined) query.problem = args.problem;
    if (args.capture !== undefined) query.capture = args.capture;
    if (args.captureAfter !== undefined) query.captureAfter = args.captureAfter;
    if (args.play !== undefined) query.play = args.play;
    if (args.vr !== undefined) query.vr = args.vr;
    if (args.vrLan) query.vrLan = "1";
    if (args.vrNoPin) query.vrNoPin = "1";
    window.loadFile(path.join(__dirname, "index.html"), { query });
});

// The page asks the user for a file and waits for the answer: `kind` is `open`, `save` or `export`, `name` the name suggested.
ipcMain.on("choose", (event, kind: string, name: string) => {
    const window = BrowserWindow.fromWebContents(event.sender);
    const graphs = [{ name: "Graphs", extensions: ["dot", "gv"] }, { name: "All files", extensions: ["*"] }];
    const pictures = [{ name: "SVG", extensions: ["svg"] }];
    const chosen = window === null ? undefined
        : kind === "open" ? dialog.showOpenDialogSync(window, { properties: ["openFile"], filters: graphs })?.[0]
        : dialog.showSaveDialogSync(window, { defaultPath: name, filters: kind === "export" ? pictures : graphs });
    event.returnValue = chosen ?? null;
});

app.on("window-all-closed", () => app.quit());
