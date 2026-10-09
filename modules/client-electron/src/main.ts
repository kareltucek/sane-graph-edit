import { app, BrowserWindow } from "electron";
import { createHash } from "crypto";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";

// sane-graph-edit-electron [--url <ws address>] <file>
//
// Shows <file> as a client of the editor session of the window
// running in this directory, which must have been started with
// --ws. Without --url the address is read from the file that
// window keeps beside its session socket.

// For tests: --capture <png> saves the picture of the graph a moment after it was first drawn, and quits.
// --shots <directory> draws the window off the screen and saves a picture of all of it, the node's editor included, several times a second.

const SHOT_INTERVAL_MS = 250;

interface Args {
    url?: string;
    file?: string;
    capture?: string;
    captureAfter?: string;
    play?: string;
    shots?: string;
    problem?: string;
}

function parseArgs(argv: string[]): Args {
    const args: Args = {};
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a === "--url") args.url = argv[++i];
        else if (a === "--capture") args.capture = path.resolve(argv[++i]);
        else if (a === "--capture-after") args.captureAfter = argv[++i];
        else if (a === "--play") args.play = path.resolve(argv[++i]);
        else if (a === "--shots") args.shots = path.resolve(argv[++i]);
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

function resolve(args: Args): Args {
    if (args.file === undefined) return { ...args, problem: "no file given" };
    if (args.url !== undefined) return args;
    const file = addressFile(process.cwd());
    try {
        return { ...args, url: fs.readFileSync(file, "utf8").trim() };
    } catch {
        return { ...args, problem: `no window with --ws is running in ${process.cwd()} (looked for ${file})` };
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
        setInterval(() => {
            window.webContents.capturePage().then((image) => fs.writeFileSync(path.join(shots, `${Date.now() - started}.png`), image.toPNG()));
        }, SHOT_INTERVAL_MS);
    }
    window.setMenuBarVisibility(false);
    const query: Record<string, string> = {};
    if (args.url !== undefined) query.url = args.url;
    if (args.file !== undefined) query.file = args.file;
    if (args.problem !== undefined) query.problem = args.problem;
    if (args.capture !== undefined) query.capture = args.capture;
    if (args.captureAfter !== undefined) query.captureAfter = args.captureAfter;
    if (args.play !== undefined) query.play = args.play;
    window.loadFile(path.join(__dirname, "index.html"), { query });
});

app.on("window-all-closed", () => app.quit());
