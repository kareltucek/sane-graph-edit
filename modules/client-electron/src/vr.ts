// The page a headset's browser shows the graph with (see vrServer.ts): it places what it is sent in space and holds nothing
// else. Reloading it loses nothing. It works in an ordinary browser too, where the graph is turned with the mouse.

import * as THREE from "three";
import { GRAPH_AT, METRES_PER_UNIT, ROOM_COLOUR } from "./room";
import type { VrNode, VrScene } from "./vrServer";

/** Where the middle of the graph is put when it arrives. */
const START = new THREE.Vector3(...GRAPH_AT);
const ARROW = 5;
/** How wide an edge is, in the graph's units (in a headset: millimetres). */
const EDGE_WIDTH = 2;

const canvas = document.getElementById("view") as HTMLCanvasElement;
const state = document.getElementById("state") as HTMLSpanElement;
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
renderer.xr.enabled = true;
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(60, 1, 0.01, 200);
camera.position.set(0, 1.5, 0.6);
camera.lookAt(START);

/** The graph: its own units inside, y down and z away as the graph has them; what places it in the room is this group. */
const graph = new THREE.Group();
graph.position.copy(START);
graph.scale.set(METRES_PER_UNIT, -METRES_PER_UNIT, -METRES_PER_UNIT);
scene.add(graph);

/** The atlas: a texture per page, and the nodes of that page as one mesh. */
const textures: THREE.Texture[] = [];
const pictured: THREE.Mesh<THREE.BufferGeometry, THREE.MeshBasicMaterial>[] = [];
/** The nodes that have no picture yet, as plain rectangles; and the edges. */
const plain = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.DoubleSide }));
const edges = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ side: THREE.DoubleSide }));
const heads = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ side: THREE.DoubleSide }));
graph.add(plain, edges, heads);
// what they are made of changes: where they were when first drawn says nothing of whether they are in view
[plain, edges, heads].forEach((it) => {
    it.frustumCulled = false;
});

let shown: VrScene | null = null;
let centred = false;

function textureOf(page: number): THREE.Texture {
    while (textures.length <= page) {
        const texture = new THREE.Texture();
        texture.colorSpace = THREE.SRGBColorSpace;
        texture.anisotropy = renderer.capabilities.getMaxAnisotropy();
        // the pictures have y down, as the pages are sent
        texture.flipY = false;
        textures.push(texture);
        const mesh = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ map: texture, alphaTest: 0.5, side: THREE.DoubleSide }));
        mesh.frustumCulled = false;
        pictured.push(mesh);
        graph.add(mesh);
    }
    return textures[page];
}

/** Lay out what was sent: the nodes of each page as rectangles with their part of the page on them, the rest plain, and the edges. */
function show(sent: VrScene) {
    shown = sent;
    const byPage = new Map<number, VrNode[]>();
    sent.nodes.filter((n) => n.page !== undefined).forEach((n) => byPage.set(n.page!, [...(byPage.get(n.page!) ?? []), n]));
    byPage.forEach((nodes, page) => {
        textureOf(page);
        const positions: number[] = [];
        const places: number[] = [];
        const indices: number[] = [];
        nodes.forEach((n, i) => {
            const left = n.x - n.middleX! / n.pixelsPerUnit!;
            const top = n.y - n.middleY! / n.pixelsPerUnit!;
            const right = left + n.pw! / n.pixelsPerUnit!;
            const bottom = top + n.ph! / n.pixelsPerUnit!;
            positions.push(left, top, n.z, right, top, n.z, left, bottom, n.z, right, bottom, n.z);
            const u1 = n.px! / sent.pageSide;
            const u2 = (n.px! + n.pw!) / sent.pageSide;
            const v1 = n.py! / sent.pageSide;
            const v2 = (n.py! + n.ph!) / sent.pageSide;
            places.push(u1, v1, u2, v1, u1, v2, u2, v2);
            indices.push(i * 4, i * 4 + 1, i * 4 + 2, i * 4 + 1, i * 4 + 3, i * 4 + 2);
        });
        const geometry = pictured[page].geometry;
        geometry.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
        geometry.setAttribute("uv", new THREE.Float32BufferAttribute(places, 2));
        geometry.setIndex(indices);
    });
    pictured.forEach((mesh, page) => {
        mesh.visible = byPage.has(page);
    });

    const positions: number[] = [];
    const colours: number[] = [];
    const indices: number[] = [];
    sent.nodes.filter((n) => n.page === undefined).forEach((n, i) => {
        const c = new THREE.Color(n.fill);
        positions.push(n.x - n.width / 2, n.y - n.height / 2, n.z, n.x + n.width / 2, n.y - n.height / 2, n.z,
            n.x - n.width / 2, n.y + n.height / 2, n.z, n.x + n.width / 2, n.y + n.height / 2, n.z);
        for (let k = 0; k < 4; k++) colours.push(c.r, c.g, c.b);
        indices.push(i * 4, i * 4 + 1, i * 4 + 2, i * 4 + 1, i * 4 + 3, i * 4 + 2);
    });
    plain.geometry.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
    plain.geometry.setAttribute("color", new THREE.Float32BufferAttribute(colours, 3));
    plain.geometry.setIndex(indices);

    // an edge from border to border, as a line, with a small triangle where it arrives
    const lines: number[] = [];
    const arrows: number[] = [];
    const reach = (n: VrNode, dx: number, dy: number) =>
        Math.min(dx === 0 ? Infinity : n.width / 2 / Math.abs(dx), dy === 0 ? Infinity : n.height / 2 / Math.abs(dy));
    sent.edges.forEach(([from, to]) => {
        const a = sent.nodes[from];
        const b = sent.nodes[to];
        const length = Math.hypot(b.x - a.x, b.y - a.y);
        // two nodes one behind the other: from middle to middle
        const dx = length > 0 ? (b.x - a.x) / length : 0;
        const dy = length > 0 ? (b.y - a.y) / length : 0;
        const out = length > 0 ? Math.min(reach(a, dx, dy), length / 2) : 0;
        const into = length > 0 ? Math.min(reach(b, dx, dy), length / 2) : 0;
        const x2 = b.x - dx * into;
        const y2 = b.y - dy * into;
        // a strip, not a line: a line is a pixel wide however near it is. Flat as the nodes are; across x for one that goes straight away
        const wx = (length > 0 ? -dy : 1) * EDGE_WIDTH / 2;
        const wy = (length > 0 ? dx : 0) * EDGE_WIDTH / 2;
        const x1 = a.x + dx * out;
        const y1 = a.y + dy * out;
        lines.push(x1 - wx, y1 - wy, a.z, x1 + wx, y1 + wy, a.z, x2 - wx, y2 - wy, b.z, x1 + wx, y1 + wy, a.z, x2 + wx, y2 + wy, b.z, x2 - wx, y2 - wy, b.z);
        arrows.push(x2, y2, b.z, x2 - dx * ARROW * 2 - dy * ARROW, y2 - dy * ARROW * 2 + dx * ARROW, b.z, x2 - dx * ARROW * 2 + dy * ARROW, y2 - dy * ARROW * 2 - dx * ARROW, b.z);
    });
    edges.geometry.setAttribute("position", new THREE.Float32BufferAttribute(lines, 3));
    edges.material.color.set(sent.ink);
    heads.geometry.setAttribute("position", new THREE.Float32BufferAttribute(arrows, 3));
    heads.material.color.set(sent.ink);

    if (!centred && sent.nodes.length > 0) {
        // the middle of the graph where the group is: in front of the eyes
        const box = new THREE.Box3().setFromArray(sent.nodes.flatMap((n) => [n.x, n.y, n.z]));
        const middle = box.getCenter(new THREE.Vector3());
        [...pictured, plain, edges, heads].forEach((it) => it.position.copy(middle).negate());
        offset.copy(middle).negate();
        centred = true;
    }
    pictured.forEach((mesh) => mesh.position.copy(offset));
    state.textContent = `${sent.nodes.length} nodes`;
}

/** Where the graph's own origin is in the group: the middle of the graph is put at the group's. */
const offset = new THREE.Vector3();

// --- what the server sends: the scene as text, a page as its number and then its picture ---

/** Says something to the window; null while not connected. And the room as it was last told of. */
let tell: ((text: string) => void) | null = null;
let toldOfRoom = "";

function connect() {
    const address = new URL(window.location.href);
    // over TLS when the page came so
    address.protocol = address.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(address);
    socket.binaryType = "arraybuffer";
    socket.onopen = () => {
        state.textContent = "connected";
        // the window may be a new one: it is told of the room again
        toldOfRoom = "";
        tell = (text) => socket.send(text);
    };
    socket.onmessage = (message) => {
        if (typeof message.data === "string") {
            const sent = JSON.parse(message.data);
            if (sent.type === "scene") show(sent as VrScene);
        } else {
            const data = message.data as ArrayBuffer;
            const page = new DataView(data).getUint32(0);
            createImageBitmap(new Blob([data.slice(4)], { type: "image/png" })).then((picture) => {
                const texture = textureOf(page);
                texture.image = picture;
                texture.needsUpdate = true;
            });
        }
    };
    // the window went away, or the cable: try again, what is shown stays meanwhile
    socket.onclose = () => {
        tell = null;
        state.textContent = "not connected; trying again…";
        setTimeout(connect, 2000);
    };
}

// --- in the headset: enter, and take the graph in the hand ---

function enter(mode: "immersive-ar" | "immersive-vr") {
    navigator.xr!.requestSession(mode, { optionalFeatures: ["local-floor", "plane-detection", "mesh-detection"] }).then((session) => {
        renderer.xr.setReferenceSpaceType("local-floor");
        // text is read here: more pixels than a headset draws with unless asked, and as many at the rim of the view as in its middle
        renderer.xr.setFramebufferScaleFactor(FRAMEBUFFER_SCALE);
        renderer.xr.setFoveation(0);
        // with the room visible nothing is drawn behind the graph
        scene.background = mode === "immersive-vr" && shown !== null ? new THREE.Color(shown.background) : null;
        return renderer.xr.setSession(session);
    }).catch((error) => {
        state.textContent = `could not enter: ${error}`;
    });
}

function offer(button: HTMLButtonElement, mode: "immersive-ar" | "immersive-vr") {
    navigator.xr?.isSessionSupported(mode).then((supported) => {
        button.disabled = !supported;
        button.onclick = () => enter(mode);
    }).catch(() => {});
}

offer(document.getElementById("enter-ar") as HTMLButtonElement, "immersive-ar");
offer(document.getElementById("enter-vr") as HTMLButtonElement, "immersive-vr");

// --- the room: the flat surfaces the headset knows of (walls, floor, a desk), each as its outline with what it is called ---

/** The surfaces shown, each with when it last changed: redrawn when that moves on. In the room, not in the graph. */
const surfaces = new Map<XRPlane, { shown: THREE.Group; changed: number }>();
const room = new THREE.Group();
scene.add(room);

/** A word, as a small picture that faces whoever looks. */
function word(text: string): THREE.Sprite {
    const picture = document.createElement("canvas");
    picture.width = 512;
    picture.height = 128;
    const pen = picture.getContext("2d")!;
    pen.font = "64px sans-serif";
    pen.textAlign = "center";
    pen.textBaseline = "middle";
    pen.fillStyle = `#${ROOM_COLOUR.toString(16).padStart(6, "0")}`;
    pen.fillText(text, 256, 64);
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(picture), transparent: true, depthTest: false }));
    sprite.scale.set(0.4, 0.1, 1);
    return sprite;
}

function forget(shown: THREE.Group) {
    room.remove(shown);
    shown.traverse((it) => {
        const drawn = it as Partial<THREE.Line<THREE.BufferGeometry, THREE.Material>>;
        drawn.geometry?.dispose();
        drawn.material?.dispose();
    });
}

/** The outline of a surface in its own space, where it lies flat (y is its normal), and its name in the middle. */
function outline(plane: XRPlane): THREE.Group {
    const shown = new THREE.Group();
    shown.matrixAutoUpdate = false;
    const corners = plane.polygon.map((p) => new THREE.Vector3(p.x, 0, p.z));
    shown.add(new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(corners), new THREE.LineBasicMaterial({ color: ROOM_COLOUR })));
    const name = word(plane.semanticLabel ?? plane.orientation);
    name.position.copy(new THREE.Box3().setFromPoints(corners).getCenter(new THREE.Vector3()));
    shown.add(name);
    return shown;
}

renderer.xr.addEventListener("planesdetected", (event) => {
    // three.js says a set of planes here, and gives the frame
    const frame = event.data as unknown as XRFrame;
    const planes = frame.detectedPlanes ?? new Set<XRPlane>();
    const space = renderer.xr.getReferenceSpace();
    [...surfaces.keys()].filter((it) => !planes.has(it)).forEach((gone) => {
        forget(surfaces.get(gone)!.shown);
        surfaces.delete(gone);
    });
    planes.forEach((plane) => {
        const had = surfaces.get(plane);
        if (had === undefined || had.changed !== plane.lastChangedTime) {
            if (had !== undefined) forget(had.shown);
            const shown = outline(plane);
            room.add(shown);
            surfaces.set(plane, { shown, changed: plane.lastChangedTime });
        }
        const pose = space === null ? undefined : frame.getPose(plane.planeSpace, space);
        const shown = surfaces.get(plane)!.shown;
        shown.visible = pose !== undefined && pose !== null;
        if (pose) shown.matrix.fromArray(pose.transform.matrix);
    });
    report(planes);
});

const REPORT_MS = 1000;
let reported = 0;

/** Tell the window of the room, when it changed: to the millimetre, so that what only trembles is not told of again and again. */
function report(planes: XRPlaneSet) {
    if (tell !== null && performance.now() - reported > REPORT_MS) {
        reported = performance.now();
        const round = (n: number) => Math.round(n * 1000) / 1000;
        const text = JSON.stringify({
            type: "room",
            surfaces: [...planes].filter((it) => surfaces.get(it)?.shown.visible).map((it) => ({
                label: it.semanticLabel ?? it.orientation,
                orientation: it.orientation,
                matrix: surfaces.get(it)!.shown.matrix.toArray().map(round),
                polygon: it.polygon.map((p) => [round(p.x), round(p.z)]),
            })),
        });
        if (text !== toldOfRoom) {
            tell(text);
            toldOfRoom = text;
        }
    }
}

// --- the room's shape: what the headset has scanned of it (furniture too), as a net of faint lines. Shown only; the window is not told of it yet ---

/** As WebXR gives a scanned shape; not in the types this is built with. */
interface ScannedMesh {
    meshSpace: XRSpace;
    vertices: Float32Array;
    indices: Uint32Array;
    lastChangedTime: number;
}

const scanned = new Map<ScannedMesh, { shown: THREE.LineSegments; changed: number }>();

function showScanned(frame: XRFrame) {
    const meshes: Set<ScannedMesh> = (frame as unknown as { detectedMeshes?: Set<ScannedMesh> }).detectedMeshes ?? new Set();
    const space = renderer.xr.getReferenceSpace();
    [...scanned.keys()].filter((it) => !meshes.has(it)).forEach((gone) => {
        forget(scanned.get(gone)!.shown as unknown as THREE.Group);
        scanned.delete(gone);
    });
    meshes.forEach((mesh) => {
        const had = scanned.get(mesh);
        if (had === undefined || had.changed !== mesh.lastChangedTime) {
            if (had !== undefined) forget(had.shown as unknown as THREE.Group);
            const shape = new THREE.BufferGeometry();
            shape.setAttribute("position", new THREE.BufferAttribute(new Float32Array(mesh.vertices), 3));
            shape.setIndex(new THREE.BufferAttribute(new Uint32Array(mesh.indices), 1));
            const shown = new THREE.LineSegments(new THREE.WireframeGeometry(shape), new THREE.LineBasicMaterial({ color: ROOM_COLOUR, transparent: true, opacity: 0.25 }));
            shape.dispose();
            shown.matrixAutoUpdate = false;
            room.add(shown);
            scanned.set(mesh, { shown, changed: mesh.lastChangedTime });
        }
        const pose = space === null ? undefined : frame.getPose(mesh.meshSpace, space);
        const shown = scanned.get(mesh)!.shown;
        shown.visible = pose !== undefined && pose !== null;
        if (pose) shown.matrix.fromArray(pose.transform.matrix);
    });
}

// --- the controllers ---
// Left: its grip takes the graph in that hand (it goes where the hand goes, turned as the hand turns, until let go); X asks into
// explore mode or out of it. Right: it points, and on the node it points at its trigger is the left button of a mouse and its grip
// the right one. What a button then does is the window's to say; here a node is only pointed at.

const RAY = 0x8899aa;
const RAY_EXPLORING = 0xe0a030;
/** How far a ray is drawn when it points at nothing, in metres. */
const RAY_LENGTH = 0.5;
/** How many times the pixels a headset draws with unless asked. */
const FRAMEBUFFER_SCALE = 1.5;
/** The button X of the left controller, as a headset numbers the buttons of one. */
const BUTTON_X = 4;
const BUTTON_Y = 5;

const hands = [0, 1].map((index) => {
    const hand = renderer.xr.getController(index);
    const ray = new THREE.Line(
        new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0, 0), new THREE.Vector3(0, 0, -1)]),
        new THREE.LineBasicMaterial({ color: RAY }),
    );
    ray.scale.z = RAY_LENGTH;
    hand.add(ray);
    scene.add(hand);
    const state = { hand, ray, side: "none" as XRHandedness, source: null as XRInputSource | null, x: false, y: false };
    hand.addEventListener("connected", (event) => {
        state.source = event.data as XRInputSource;
        state.side = state.source.handedness;
    });
    hand.addEventListener("disconnected", () => {
        state.source = null;
        state.side = "none";
    });
    const click = (button: 0 | 2) => {
        if (state.side === "right" && pointed !== null) tell?.(JSON.stringify({ type: "click", id: pointed.id, button }));
    };
    hand.addEventListener("selectstart", () => click(0));
    hand.addEventListener("squeezestart", () => {
        if (state.side === "left") hand.attach(graph);
        else click(2);
    });
    hand.addEventListener("squeezeend", () => {
        if (state.side === "left") scene.attach(graph);
    });
    return state;
});

/** The node the right controller points at, and a frame around it. */
let pointed: VrNode | null = null;
const frameAround = new THREE.LineLoop(
    new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(-0.5, -0.5, 0), new THREE.Vector3(0.5, -0.5, 0), new THREE.Vector3(0.5, 0.5, 0), new THREE.Vector3(-0.5, 0.5, 0)]),
    new THREE.LineBasicMaterial({ color: RAY_EXPLORING, depthTest: false }),
);
frameAround.visible = false;
graph.add(frameAround);

/** Find what the right controller points at: of the nodes its ray goes through, the nearest. The ray is drawn up to it. */
function point() {
    const right = hands.find((it) => it.side === "right");
    let nearest: { node: VrNode; along: number } | null = null;
    if (right !== undefined && shown !== null) {
        // the ray in the graph's own space, where a node is a rectangle at its depth
        graph.updateMatrixWorld();
        right.hand.updateMatrixWorld();
        const into = graph.matrixWorld.clone().invert();
        const from = new THREE.Vector3().setFromMatrixPosition(right.hand.matrixWorld).applyMatrix4(into).sub(offset);
        const towards = new THREE.Vector3(0, 0, -1).transformDirection(right.hand.matrixWorld).add(new THREE.Vector3().setFromMatrixPosition(right.hand.matrixWorld))
            .applyMatrix4(into).sub(offset).sub(from);
        shown.nodes.forEach((n) => {
            // [along] in metres: [towards] is a metre of the ray
            const along = towards.z === 0 ? -1 : (n.z - from.z) / towards.z;
            const hit = along > 0 && (nearest === null || along < nearest.along) &&
                Math.abs(from.x + towards.x * along - n.x) <= n.width / 2 && Math.abs(from.y + towards.y * along - n.y) <= n.height / 2;
            if (hit) nearest = { node: n, along };
        });
    }
    const found = nearest as { node: VrNode; along: number } | null;
    pointed = found?.node ?? null;
    frameAround.visible = found !== null;
    if (found !== null) {
        frameAround.position.set(found.node.x, found.node.y, found.node.z).add(offset);
        frameAround.scale.set(found.node.width + 6, found.node.height + 6, 1);
    }
    hands.forEach((it) => {
        it.ray.scale.z = it === right && found !== null ? found.along : RAY_LENGTH;
        it.ray.material.color.set(it === right && shown?.exploring ? RAY_EXPLORING : RAY);
    });
}

/** A thumbstick pushed forward or back makes the graph bigger or smaller, where it is; X of the left controller, when it goes down, asks into explore mode or out of it. */
function buttons() {
    hands.forEach((it) => {
        const pad = it.source?.gamepad;
        const push = pad?.axes[3] ?? 0;
        if (Math.abs(push) > 0.2) graph.scale.multiplyScalar(1 - push * 0.02);
        const x = it.side === "left" && pad?.buttons[BUTTON_X]?.pressed === true;
        if (x && !it.x) tell?.(JSON.stringify({ type: "explore" }));
        it.x = x;
        // Y: the headset's own scan of the room, anew, where its browser can start that (once in a session)
        const y = it.side === "left" && pad?.buttons[BUTTON_Y]?.pressed === true;
        if (y && !it.y) (renderer.xr.getSession() as unknown as { initiateRoomCapture?: () => Promise<void> } | null)?.initiateRoomCapture?.().catch(() => {});
        it.y = y;
    });
}

// --- in an ordinary browser: the mouse turns the graph, the wheel brings it nearer ---

canvas.addEventListener("pointermove", (e) => {
    if (e.buttons !== 0 && !renderer.xr.isPresenting) {
        graph.rotation.y += e.movementX * 0.005;
        graph.rotation.x += e.movementY * 0.005;
    }
});
canvas.addEventListener("wheel", (e) => {
    if (!renderer.xr.isPresenting) graph.scale.multiplyScalar(e.deltaY < 0 ? 1.1 : 1 / 1.1);
});

renderer.setAnimationLoop((_time, frame) => {
    if (frame) showScanned(frame);
    if (!renderer.xr.isPresenting) {
        const width = canvas.clientWidth;
        const height = canvas.clientHeight;
        if (canvas.width !== Math.round(width * window.devicePixelRatio) || canvas.height !== Math.round(height * window.devicePixelRatio)) {
            renderer.setPixelRatio(window.devicePixelRatio);
            renderer.setSize(width, height, false);
            camera.aspect = width / height;
            camera.updateProjectionMatrix();
        }
        scene.background = shown !== null ? new THREE.Color(shown.background) : null;
    }
    buttons();
    point();
    renderer.render(scene, camera);
});

connect();
