// The page a headset's browser shows the graph with (see vrServer.ts): it places what it is sent in space and holds nothing
// else. Reloading it loses nothing. It works in an ordinary browser too, where the graph is turned with the mouse.

import * as THREE from "three";
import type { VrNode, VrScene } from "./vrServer";

/** A unit of the graph is this many metres: a node 150 units wide is 15 cm. */
const METRES_PER_UNIT = 0.001;
/** Where the middle of the graph is put when it arrives: in front of the eyes, at arm's length and a bit. */
const START = new THREE.Vector3(0, 1.3, -1.2);
const ARROW = 5;

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
const edges = new THREE.LineSegments(new THREE.BufferGeometry(), new THREE.LineBasicMaterial());
const heads = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ side: THREE.DoubleSide }));
graph.add(plain, edges, heads);

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
        lines.push(a.x + dx * out, a.y + dy * out, a.z, x2, y2, b.z);
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

function connect() {
    const address = new URL(window.location.href);
    address.protocol = "ws:";
    const socket = new WebSocket(address);
    socket.binaryType = "arraybuffer";
    socket.onopen = () => {
        state.textContent = "connected";
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
        state.textContent = "not connected; trying again…";
        setTimeout(connect, 2000);
    };
}

// --- in the headset: enter, and take the graph in the hand ---

function enter(mode: "immersive-ar" | "immersive-vr") {
    navigator.xr!.requestSession(mode, { optionalFeatures: ["local-floor"] }).then((session) => {
        renderer.xr.setReferenceSpaceType("local-floor");
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

// Squeezing a controller takes the graph in that hand: it goes where the hand goes, turned as the hand turns, until let go.
[0, 1].forEach((index) => {
    const hand = renderer.xr.getController(index);
    scene.add(hand);
    hand.add(new THREE.Line(
        new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0, 0), new THREE.Vector3(0, 0, -0.5)]),
        new THREE.LineBasicMaterial({ color: 0x8899aa }),
    ));
    hand.addEventListener("squeezestart", () => hand.attach(graph));
    hand.addEventListener("squeezeend", () => scene.attach(graph));
});

/** A thumbstick pushed forward or back makes the graph bigger or smaller, where it is. */
function resize() {
    const session = renderer.xr.getSession();
    session?.inputSources.forEach((source) => {
        const push = source.gamepad?.axes[3] ?? 0;
        if (Math.abs(push) > 0.2) graph.scale.multiplyScalar(1 - push * 0.02);
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

renderer.setAnimationLoop(() => {
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
    resize();
    renderer.render(scene, camera);
});

connect();
