// The page a headset's browser shows the graph with (see vrServer.ts): it places what it is sent in space and holds nothing
// else. Reloading it loses nothing. It works in an ordinary browser too, where the graph is turned with the mouse.

import * as THREE from "three";
import { GRAPH_AT, METRES_PER_UNIT, ROOM_COLOUR } from "./room";
import type { VrLive, VrNode, VrScene } from "./vrServer";

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

/**
 * What of the graph is behind something of the room, as the eyes see it, is still shown, this faint: the graph is where one
 * put it, whatever stands there. The room's shapes are drawn first, unseen, only as how far away they are ([hides]); then each
 * mesh of the graph once more ([behind]), faint, and only where it is further than that; then the graph as ever, which
 * what is nearer hides. So an edge that goes into a table turns faint just where it enters it.
 */
const BEHIND_OPACITY = 0.1;
const ORDER_ROOM = -10;
const ORDER_BEHIND = -5;
const twins: { of: THREE.Mesh<THREE.BufferGeometry, THREE.MeshBasicMaterial>; twin: THREE.Mesh<THREE.BufferGeometry, THREE.MeshBasicMaterial> }[] = [];

function behind(of: THREE.Mesh<THREE.BufferGeometry, THREE.MeshBasicMaterial>) {
    const material = of.material.clone();
    // blended, yet drawn among what is not see-through: before the graph itself, or one node would show faint through another
    material.blending = THREE.CustomBlending;
    material.blendSrc = THREE.SrcAlphaFactor;
    material.blendDst = THREE.OneMinusSrcAlphaFactor;
    material.opacity = BEHIND_OPACITY;
    material.alphaTest = of.material.alphaTest * BEHIND_OPACITY;
    material.depthFunc = THREE.GreaterDepth;
    material.depthWrite = false;
    const twin = new THREE.Mesh(of.geometry, material);
    twin.frustumCulled = false;
    twin.renderOrder = ORDER_BEHIND;
    of.add(twin);
    twins.push({ of, twin });
}

/** [shape] of the room as something that hides: nothing of it is seen, what is behind it is not drawn. */
function hides(shape: THREE.BufferGeometry): THREE.Mesh {
    const hiding = new THREE.Mesh(shape, new THREE.MeshBasicMaterial({ colorWrite: false, side: THREE.DoubleSide }));
    hiding.renderOrder = ORDER_ROOM;
    hiding.frustumCulled = false;
    return hiding;
}

[plain, edges, heads].forEach(behind);
// what they are made of changes: where they were when first drawn says nothing of whether they are in view
[plain, edges, heads].forEach((it) => {
    it.frustumCulled = false;
});

let shown: VrScene | null = null;
/** The scene as the window sent it, and the nodes that are shown elsewhere than it says, each until when. */
let received: VrScene | null = null;
const placed = new Map<string, { at: { x: number; y: number; z: number }; until: number }>();
let centred = false;
/** The graph is on the walls of the room ([VrScene.inRoom]), not before the eyes. */
let inRoom = false;

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
        behind(mesh);
    }
    return textures[page];
}

/** Lay out what was sent: the nodes of each page as rectangles with their part of the page on them, the rest plain, and the edges. */
function show(got: VrScene) {
    received = got;
    // a node that is held, or was just let go of, is where the hand put it: the window says so only a little later
    const now = performance.now();
    [...placed].filter(([, place]) => place.until < now).forEach(([id]) => placed.delete(id));
    const sent: VrScene = { ...got, nodes: got.nodes.map((n) => placed.has(n.id) ? { ...n, ...placed.get(n.id)!.at } : n) };
    // another graph altogether (a link was followed, another tab is shown): its middle is put before the eyes, as the first one's was
    const before = new Set(shown?.nodes.map((n) => n.id));
    if (shown !== null && sent.nodes.length > 0 && !sent.nodes.some((n) => before.has(n.id))) centred = false;
    shown = sent;
    // what was drawn over a node that is there no more
    const there = new Set(sent.nodes.map((n) => n.id));
    [...lives.keys()].filter((id) => !there.has(id)).forEach(forgetLive);
    const byPage = new Map<number, VrNode[]>();
    sent.nodes.filter((n) => n.page !== undefined).forEach((n) => byPage.set(n.page!, [...(byPage.get(n.page!) ?? []), n]));
    // where the corners are is said anew at every frame ([face]): a node faces whoever looks
    byPage.forEach((nodes, page) => {
        textureOf(page);
        const places: number[] = [];
        const indices: number[] = [];
        nodes.forEach((n, i) => {
            const u1 = n.px! / sent.pageSide;
            const u2 = (n.px! + n.pw!) / sent.pageSide;
            const v1 = n.py! / sent.pageSide;
            const v2 = (n.py! + n.ph!) / sent.pageSide;
            places.push(u1, v1, u2, v1, u1, v2, u2, v2);
            indices.push(i * 4, i * 4 + 1, i * 4 + 2, i * 4 + 1, i * 4 + 3, i * 4 + 2);
        });
        const geometry = pictured[page].geometry;
        geometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(nodes.length * 12), 3));
        geometry.setAttribute("uv", new THREE.Float32BufferAttribute(places, 2));
        geometry.setIndex(indices);
    });
    pictured.forEach((mesh, page) => {
        mesh.visible = byPage.has(page);
    });

    const colours: number[] = [];
    const indices: number[] = [];
    const unpictured = sent.nodes.filter((n) => n.page === undefined);
    unpictured.forEach((n, i) => {
        const c = new THREE.Color(n.fill);
        for (let k = 0; k < 4; k++) colours.push(c.r, c.g, c.b);
        indices.push(i * 4, i * 4 + 1, i * 4 + 2, i * 4 + 1, i * 4 + 3, i * 4 + 2);
    });
    plain.geometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(unpictured.length * 12), 3));
    plain.geometry.setAttribute("color", new THREE.Float32BufferAttribute(colours, 3));
    plain.geometry.setIndex(indices);
    laid = { index: new Map(sent.nodes.map((n, i) => [n, i])), pages: byPage, plain: unpictured, axes: new Float32Array(sent.nodes.length * 9) };

    edges.geometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(sent.edges.length * 18), 3));
    edges.material.color.set(sent.ink);
    heads.geometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(sent.edges.length * 9), 3));
    heads.material.color.set(sent.ink);
    twins.forEach(({ of, twin }) => twin.material.color.copy(of.material.color));

    if (sent.inRoom !== inRoom) {
        // onto the walls: the graph's units are the room's then, from the room's own origin; off them: before the eyes again
        inRoom = sent.inRoom;
        scene.attach(graph);
        graph.position.copy(inRoom ? new THREE.Vector3() : START);
        graph.quaternion.identity();
        graph.scale.set(METRES_PER_UNIT, -METRES_PER_UNIT, -METRES_PER_UNIT);
        centred = false;
        // out of a headset the eyes are where they were at first again ([lookAround] moved them)
        camera.position.set(0, 1.5, 0.6);
        camera.lookAt(START);
    }
    showWalls(sent.walls ?? []);
    if (inRoom) {
        offset.set(0, 0, 0);
        [plain, edges, heads].forEach((it) => it.position.set(0, 0, 0));
    } else if (!centred && sent.nodes.length > 0) {
        // the middle of the graph where the group is: in front of the eyes
        const box = new THREE.Box3().setFromArray(sent.nodes.flatMap((n) => [n.x, n.y, n.z]));
        const middle = box.getCenter(new THREE.Vector3());
        [...pictured, plain, edges, heads].forEach((it) => it.position.copy(middle).negate());
        offset.copy(middle).negate();
        centred = true;
    }
    pictured.forEach((mesh) => mesh.position.copy(offset));
    state.textContent = `${sent.nodes.length} nodes`;
    writeStatus(sent.status ?? "");
    face();
}

// --- the status line: a strip that stays where it is in the room, before and below the eyes, and comes along when one turns or walks away from it ---

const STATUS_PIXELS = [1536, 64];
/** How wide and high the strip is, how far before the eyes and how far below them, in metres. */
const STATUS_SIZE = [0.9, 0.0375];
const STATUS_AWAY = 1;
const STATUS_BELOW = 0.5;
/** It comes along when it is further to the side than this (radians), or nearer or further than these (metres). */
const STATUS_ASIDE = 0.7;
const STATUS_NEAR = 0.6;
const STATUS_FAR = 1.5;

const statusPicture = document.createElement("canvas");
[statusPicture.width, statusPicture.height] = STATUS_PIXELS;
const statusTexture = new THREE.CanvasTexture(statusPicture);
statusTexture.colorSpace = THREE.SRGBColorSpace;
const statusStrip = new THREE.Mesh(
    new THREE.PlaneGeometry(STATUS_SIZE[0], STATUS_SIZE[1]),
    new THREE.MeshBasicMaterial({ map: statusTexture, transparent: true, depthTest: false }),
);
statusStrip.renderOrder = 10;
statusStrip.visible = false;
scene.add(statusStrip);
let statusWritten: string | null = null;
/** Where the strip is going; null until it is first put somewhere. */
let statusGoal: THREE.Vector3 | null = null;

/** Draw [text] on the strip: as text, whatever is in it. */
function writeStatus(text: string) {
    if (text !== statusWritten) {
        statusWritten = text;
        const pen = statusPicture.getContext("2d")!;
        pen.clearRect(0, 0, statusPicture.width, statusPicture.height);
        pen.fillStyle = "rgba(20, 20, 20, 0.8)";
        pen.fillRect(0, 0, statusPicture.width, statusPicture.height);
        pen.font = "36px monospace";
        pen.textBaseline = "middle";
        pen.fillStyle = "#dddddd";
        pen.fillText(text, 16, statusPicture.height / 2);
        statusTexture.needsUpdate = true;
    }
}

/** Keep the strip before whoever looks: left where it is while it is about there, moved (not at once) when it no longer is. */
function placeStatus() {
    statusStrip.visible = renderer.xr.isPresenting;
    if (renderer.xr.isPresenting) {
        const eyes = renderer.xr.getCamera();
        const head = new THREE.Vector3().setFromMatrixPosition(eyes.matrixWorld);
        const ahead = new THREE.Vector3(0, 0, -1).transformDirection(eyes.matrixWorld).setY(0);
        // looking straight up or down says nothing of where one faces: it stays
        if (ahead.lengthSq() > 0.01) {
            ahead.normalize();
            const to = statusGoal === null ? null : statusGoal.clone().sub(head).setY(0);
            const fine = to !== null && to.length() >= STATUS_NEAR && to.length() <= STATUS_FAR && to.angleTo(ahead) <= STATUS_ASIDE;
            // once it comes along it does so all the way, to wherever one is facing by then: not in steps
            statusComing = statusComing || !fine;
            if (statusComing) {
                const first = statusGoal === null;
                statusGoal = head.clone().addScaledVector(ahead, STATUS_AWAY).setY(head.y - STATUS_BELOW);
                if (first) statusStrip.position.copy(statusGoal);
            }
        }
        const now = performance.now();
        const seconds = Math.min(0.1, (now - statusMovedAt) / 1000);
        statusMovedAt = now;
        if (statusGoal !== null) {
            // as a damped spring: it sets off gently and arrives gently, the same at any frame rate
            const omega = 2 / STATUS_EASE_S;
            const x = omega * seconds;
            const decay = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x);
            const off = statusStrip.position.clone().sub(statusGoal);
            const step = statusSpeed.clone().addScaledVector(off, omega).multiplyScalar(seconds);
            statusSpeed.addScaledVector(step, -omega).multiplyScalar(decay);
            statusStrip.position.copy(statusGoal).add(off.add(step).multiplyScalar(decay));
            if (statusStrip.position.distanceTo(statusGoal) < STATUS_ARRIVED && statusSpeed.length() < STATUS_ARRIVED) statusComing = false;
        }
        statusStrip.lookAt(head);
    }
}

/** The strip is on its way to before the eyes; how fast it moves (metres a second), and when it was last moved. */
let statusComing = false;
const statusSpeed = new THREE.Vector3();
let statusMovedAt = 0;
/** About how long it takes to come along, in seconds; and how near its place (metres) it has arrived. */
const STATUS_EASE_S = 0.6;
const STATUS_ARRIVED = 0.03;

/** Where the graph's own origin is in the group: the middle of the graph is put at the group's. */
const offset = new THREE.Vector3();

/**
 * What [show] laid out, for [face] to put in place: which node is which in the scene, the nodes of each page and the plain
 * ones in the order their rectangles are in, and for each node of the scene which way it faces: what is right, up and towards
 * whoever looks, three numbers each.
 */
let laid: { index: Map<VrNode, number>; pages: Map<number, VrNode[]>; plain: VrNode[]; axes: Float32Array } | null = null;

/**
 * Turn every node to whoever looks, as the window does: upright, its face to the eyes, so that it is read from anywhere and
 * never from behind. The edges, strips and not lines (a line is a pixel wide however near it is), go from border to border of
 * the nodes as they are turned, and show their width to the eyes too.
 */
function face() {
    const sent = shown;
    const now = laid;
    if (sent !== null && now !== null) {
        // in the graph's own space, as the nodes are
        graph.updateMatrixWorld();
        const into = graph.matrixWorld.clone().invert();
        const eyes = renderer.xr.isPresenting ? renderer.xr.getCamera() : camera;
        const head = new THREE.Vector3().setFromMatrixPosition(eyes.matrixWorld).applyMatrix4(into).sub(offset);
        const upright = new THREE.Vector3(0, 1, 0).transformDirection(into);
        const towards = new THREE.Vector3();
        const right = new THREE.Vector3();
        const up = new THREE.Vector3();
        const axes = now.axes;
        sent.nodes.forEach((n, i) => {
            // a node on a wall lies on it, its face to the room
            if (n.tx !== undefined && n.tz !== undefined) towards.set(n.tx, 0, n.tz).normalize();
            else towards.set(head.x - n.x, head.y - n.y, head.z - n.z).normalize();
            right.crossVectors(upright, towards);
            // seen from right above or below: any way is upright
            if (right.lengthSq() < 1e-6) right.set(1, 0, 0);
            right.normalize();
            up.crossVectors(towards, right);
            axes.set([right.x, right.y, right.z, up.x, up.y, up.z, towards.x, towards.y, towards.z], i * 9);
        });
        // a corner of the node [i]: [a] to the right of its middle and [c] below it
        const corner = (to: Float32Array, at: number, n: VrNode, i: number, a: number, c: number) => {
            to[at] = n.x + axes[i * 9] * a - axes[i * 9 + 3] * c;
            to[at + 1] = n.y + axes[i * 9 + 1] * a - axes[i * 9 + 4] * c;
            to[at + 2] = n.z + axes[i * 9 + 2] * a - axes[i * 9 + 5] * c;
        };
        const rectangle = (to: Float32Array, k: number, n: VrNode, left: number, top: number, wide: number, high: number) => {
            const i = now.index.get(n)!;
            corner(to, k * 12, n, i, left, top);
            corner(to, k * 12 + 3, n, i, left + wide, top);
            corner(to, k * 12 + 6, n, i, left, top + high);
            corner(to, k * 12 + 9, n, i, left + wide, top + high);
        };
        now.pages.forEach((nodes, page) => {
            const positions = pictured[page].geometry.getAttribute("position") as THREE.BufferAttribute;
            nodes.forEach((n, k) => rectangle(positions.array as Float32Array, k, n,
                -n.middleX! / n.pixelsPerUnit!, -n.middleY! / n.pixelsPerUnit!, n.pw! / n.pixelsPerUnit!, n.ph! / n.pixelsPerUnit!));
            positions.needsUpdate = true;
        });
        // what is drawn over a node ([showLive]): turned as the node is, a little before it
        lives.forEach((live) => {
            const n = sent.nodes.find((it) => it.id === live.sent.id);
            const i = n === undefined ? undefined : now.index.get(n);
            live.mesh.visible = n !== undefined && i !== undefined;
            if (n !== undefined && i !== undefined) {
                const corners = live.mesh.geometry.getAttribute("position") as THREE.BufferAttribute;
                const to = corners.array as Float32Array;
                const { left, top, cellWidth, cellHeight, columns } = live.sent;
                const wide = columns * cellWidth;
                const high = (live.sent.rows?.length ?? 0) * cellHeight;
                [[left, top], [left + wide, top], [left, top + high], [left + wide, top + high]].forEach(([a, c], k) => {
                    corner(to, k * 3, n, i, a, c);
                    to[k * 3] += axes[i * 9 + 6] * LIVE_BEFORE;
                    to[k * 3 + 1] += axes[i * 9 + 7] * LIVE_BEFORE;
                    to[k * 3 + 2] += axes[i * 9 + 8] * LIVE_BEFORE;
                });
                corners.needsUpdate = true;
                live.mesh.position.copy(offset);
            }
        });
        const plains = plain.geometry.getAttribute("position") as THREE.BufferAttribute;
        now.plain.forEach((n, k) => rectangle(plains.array as Float32Array, k, n, -n.width / 2, -n.height / 2, n.width, n.height));
        plains.needsUpdate = true;

        const strips = edges.geometry.getAttribute("position") as THREE.BufferAttribute;
        const arrows = heads.geometry.getAttribute("position") as THREE.BufferAttribute;
        const along = new THREE.Vector3();
        const across = new THREE.Vector3();
        const p1 = new THREE.Vector3();
        const p2 = new THREE.Vector3();
        // how far from the middle of the node [i] its border is, going along [along]
        const reach = (n: VrNode, i: number) => {
            const sideways = Math.abs(along.x * axes[i * 9] + along.y * axes[i * 9 + 1] + along.z * axes[i * 9 + 2]);
            const upwards = Math.abs(along.x * axes[i * 9 + 3] + along.y * axes[i * 9 + 4] + along.z * axes[i * 9 + 5]);
            return Math.min(sideways < 1e-6 ? Infinity : n.width / 2 / sideways, upwards < 1e-6 ? Infinity : n.height / 2 / upwards);
        };
        const put = (to: THREE.BufferAttribute, at: number, p: THREE.Vector3, side: number, back: number) =>
            to.setXYZ(at, p.x + across.x * side - along.x * back, p.y + across.y * side - along.y * back, p.z + across.z * side - along.z * back);
        sent.edges.forEach(([from, to], e) => {
            const a = sent.nodes[from];
            const b = sent.nodes[to];
            along.set(b.x - a.x, b.y - a.y, b.z - a.z);
            const length = along.length();
            if (length > 0) along.divideScalar(length);
            p1.set(a.x, a.y, a.z).addScaledVector(along, Math.min(reach(a, from), length / 2));
            p2.set(b.x, b.y, b.z).addScaledVector(along, -Math.min(reach(b, to), length / 2));
            // across the edge and across the line of sight: its width is what is seen
            across.crossVectors(along, towards.set(head.x - p2.x, head.y - p2.y, head.z - p2.z));
            if (across.lengthSq() < 1e-9) across.set(1, 0, 0);
            across.normalize();
            const half = EDGE_WIDTH / 2;
            put(strips, e * 6, p1, -half, 0);
            put(strips, e * 6 + 1, p1, half, 0);
            put(strips, e * 6 + 2, p2, -half, 0);
            put(strips, e * 6 + 3, p1, half, 0);
            put(strips, e * 6 + 4, p2, half, 0);
            put(strips, e * 6 + 5, p2, -half, 0);
            put(arrows, e * 3, p2, 0, 0);
            put(arrows, e * 3 + 1, p2, ARROW, ARROW * 2);
            put(arrows, e * 3 + 2, p2, -ARROW, ARROW * 2);
        });
        strips.needsUpdate = true;
        arrows.needsUpdate = true;
    }
}

// --- nodes that change fast (terminals): what they show comes as text, as it changes, and is drawn here over the node ---

/** A cell of such a node in its picture, pixels: sharp enough from near. */
const LIVE_CELL = [14, 28];
const LIVE_FONT_PX = 22;
/** How far before its node what is drawn over it is, in the graph's units: so that it is the one seen. */
const LIVE_BEFORE = 1;

const lives = new Map<string, { sent: VrLive; picture: HTMLCanvasElement; texture: THREE.CanvasTexture; mesh: THREE.Mesh<THREE.BufferGeometry, THREE.MeshBasicMaterial> }>();

function forgetLive(id: string) {
    const was = lives.get(id);
    if (was !== undefined) {
        graph.remove(was.mesh);
        was.mesh.geometry.dispose();
        was.mesh.material.dispose();
        was.texture.dispose();
        lives.delete(id);
    }
}

/** Draw what the node [VrLive.id] shows now. The text is drawn as text: never as markup. */
function showLive(sent: VrLive) {
    const rows = sent.rows;
    if (rows === null || sent.columns <= 0 || rows.length === 0) forgetLive(sent.id);
    else {
        if (!lives.has(sent.id)) {
            const picture = document.createElement("canvas");
            const texture = new THREE.CanvasTexture(picture);
            texture.colorSpace = THREE.SRGBColorSpace;
            texture.anisotropy = renderer.capabilities.getMaxAnisotropy();
            const shape = new THREE.BufferGeometry();
            shape.setAttribute("position", new THREE.BufferAttribute(new Float32Array(12), 3));
            // the corners as [face] puts them: top left, top right, bottom left, bottom right
            shape.setAttribute("uv", new THREE.Float32BufferAttribute([0, 1, 1, 1, 0, 0, 1, 0], 2));
            shape.setIndex([0, 1, 2, 1, 3, 2]);
            const mesh = new THREE.Mesh(shape, new THREE.MeshBasicMaterial({ map: texture, side: THREE.DoubleSide }));
            mesh.frustumCulled = false;
            mesh.visible = false;
            graph.add(mesh);
            lives.set(sent.id, { sent, picture, texture, mesh });
        }
        const live = lives.get(sent.id)!;
        live.sent = sent;
        const [wide, high] = LIVE_CELL;
        const picture = live.picture;
        if (picture.width !== sent.columns * wide || picture.height !== rows.length * high) {
            picture.width = sent.columns * wide;
            picture.height = rows.length * high;
            // another size: another texture on the card
            live.texture.dispose();
        }
        const to = picture.getContext("2d")!;
        to.fillStyle = sent.background;
        to.fillRect(0, 0, picture.width, picture.height);
        to.textBaseline = "middle";
        rows.forEach((runs, row) => {
            let column = 0;
            runs.forEach(([text, ink, behind, bold]) => {
                const length = [...text].length;
                if (behind !== sent.background) {
                    to.fillStyle = behind;
                    to.fillRect(column * wide, row * high, length * wide, high);
                }
                to.font = `${bold ? "bold " : ""}${LIVE_FONT_PX}px monospace`;
                to.fillStyle = ink;
                // the font's letters as wide as the cells are, whatever font this browser has
                const advance = to.measureText("M").width;
                to.save();
                to.translate(column * wide, row * high + high / 2);
                to.scale(advance > 0 ? wide / advance : 1, 1);
                if (text.trim() !== "") to.fillText(text, 0, 0);
                to.restore();
                column += length;
            });
        });
        live.texture.needsUpdate = true;
    }
}

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
        stood = null;
        tell = (text) => socket.send(text);
    };
    socket.onmessage = (message) => {
        if (typeof message.data === "string") {
            const sent = JSON.parse(message.data);
            if (sent.type === "scene") show(sent as VrScene);
            else if (sent.type === "live") showLive(sent as VrLive);
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

/** How wide the outline of a surface is drawn, and a controller's ray, in metres. */
const OUTLINE_WIDTH = 0.01;
const RAY_WIDTH = 0.003;

/** The outline of a surface in its own space, where it lies flat (y is its normal), and its name in the middle. */
function outline(plane: XRPlane): THREE.Group {
    const shown = new THREE.Group();
    shown.matrixAutoUpdate = false;
    const corners = plane.polygon.map((p) => new THREE.Vector3(p.x, 0, p.z));
    // a strip along each side, lying in the surface: a line would be a pixel wide however near it is
    const strips = corners.flatMap((from, i) => {
        const to = corners[(i + 1) % corners.length];
        const side = new THREE.Vector3(to.z - from.z, 0, from.x - to.x).normalize().multiplyScalar(OUTLINE_WIDTH / 2);
        const [a, b, c, d] = [from.clone().sub(side), from.clone().add(side), to.clone().sub(side), to.clone().add(side)];
        return [a, b, c, b, d, c];
    });
    shown.add(new THREE.Mesh(new THREE.BufferGeometry().setFromPoints(strips), new THREE.MeshBasicMaterial({ color: ROOM_COLOUR, side: THREE.DoubleSide })));
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
            shown.add(hides(shape));
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

// A made-up thing in the room, for trying how it hides where there is no headset: a box in front of the right of the graph,
// asked for with `mock` in the page's address.
if (new URL(window.location.href).searchParams.has("mock")) {
    const box = new THREE.BoxGeometry(0.12, 0.3, 0.12);
    const shown = new THREE.LineSegments(new THREE.WireframeGeometry(box), new THREE.LineBasicMaterial({ color: ROOM_COLOUR, transparent: true, opacity: 0.25 }));
    shown.add(hides(box));
    shown.position.set(GRAPH_AT[0] + 0.1, GRAPH_AT[1], GRAPH_AT[2] + 0.2);
    room.add(shown);
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
    // a thin bar from the hand forward, a metre long before it is made as long as the ray is
    const ray = new THREE.Mesh(new THREE.BoxGeometry(RAY_WIDTH, RAY_WIDTH, 1).translate(0, 0, -0.5), new THREE.MeshBasicMaterial({ color: RAY }));
    ray.scale.z = RAY_LENGTH;
    hand.add(ray);
    scene.add(hand);
    // around the node this controller points at
    const frame = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ color: RAY_EXPLORING, depthTest: false, side: THREE.DoubleSide }));
    frame.geometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(24), 3));
    frame.geometry.setIndex([0, 1, 2, 3].flatMap((k) => [k, (k + 1) % 4, 4 + k, 4 + k, (k + 1) % 4, 4 + (k + 1) % 4]));
    frame.visible = false;
    frame.frustumCulled = false;
    frame.renderOrder = 5;
    graph.add(frame);
    const state = {
        hand, ray, frame, side: "none" as XRHandedness, source: null as XRInputSource | null, pointed: null as VrNode | null,
        x: false, y: false, a: false, b: false,
    };
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
    // the trigger takes the node pointed at; let go where it was taken, that was a click, elsewhere, the node is put there
    // The left trigger, on a node that has links: its one link is followed; of several, a menu is held open until it is let go of.
    hand.addEventListener("selectstart", () => {
        const on = state.pointed;
        const links = on?.links ?? [];
        if (state.side === "right" && pointed !== null && pointedAt !== null) {
            const middle = new THREE.Vector3(pointed.x, pointed.y, pointed.z);
            held = { id: pointed.id, along: pointedAt.along, grip: pointedAt.at.clone().sub(middle), from: middle, moved: false };
        } else if (state.side === "left" && on !== null && links.length === 1) tell?.(JSON.stringify({ type: "follow", id: on.id, letter: links[0][0] }));
        else if (state.side === "left" && on !== null && links.length > 1) openMenu(on, links);
    });
    hand.addEventListener("selectend", () => {
        if (state.side === "left") closeMenu(true);
        else {
            const was = held;
            held = null;
            const place = was === null ? undefined : placed.get(was.id);
            if (was !== null && was.moved && place !== undefined) {
                place.until = performance.now() + KEPT_AFTER_MS;
                tell?.(JSON.stringify({ type: "move", id: was.id, ...place.at }));
            } else if (was !== null && state.side === "right") tell?.(JSON.stringify({ type: "click", id: was.id, button: 0 }));
        }
    });
    hand.addEventListener("squeezestart", () => {
        if (state.side === "left") hand.attach(graph);
        else click(2);
    });
    hand.addEventListener("squeezeend", () => {
        if (state.side === "left") scene.attach(graph);
    });
    return state;
});

// --- the links of a node, as a menu around it: open while the right grip is held, and what the ray points at when it is let go of is followed ---

const MENU_ITEM = [0.24, 0.045];
/** How far from the node's middle the links are put, sideways and up or down, in metres. */
const MENU_AROUND = [0.24, 0.13];
const MENU_PIXELS = [640, 120];

let menu: { id: string; items: { letter: string; shown: THREE.Mesh<THREE.PlaneGeometry, THREE.MeshBasicMaterial> }[]; chosen: number } | null = null;

function openMenu(node: VrNode, links: string[]) {
    closeMenu(false);
    const i = laid?.index.get(node);
    if (laid !== null && i !== undefined) {
        // around the node, in the room, turned as the node is: to whoever looks
        const middle = graph.localToWorld(new THREE.Vector3(node.x, node.y, node.z).add(offset));
        const eyes = renderer.xr.isPresenting ? renderer.xr.getCamera() : camera;
        const head = new THREE.Vector3().setFromMatrixPosition(eyes.matrixWorld);
        const towards = head.clone().sub(middle).normalize();
        const right = new THREE.Vector3(0, 1, 0).cross(towards).normalize();
        const up = towards.clone().cross(right);
        const items = links.map((link, k) => {
            const picture = document.createElement("canvas");
            [picture.width, picture.height] = MENU_PIXELS;
            const pen = picture.getContext("2d")!;
            pen.fillStyle = "#202020";
            pen.fillRect(0, 0, picture.width, picture.height);
            pen.font = "44px monospace";
            pen.textBaseline = "middle";
            pen.fillStyle = "#dddddd";
            // as text, whatever is in it
            pen.fillText(link, 20, picture.height / 2);
            const texture = new THREE.CanvasTexture(picture);
            texture.colorSpace = THREE.SRGBColorSpace;
            const shown = new THREE.Mesh(new THREE.PlaneGeometry(MENU_ITEM[0], MENU_ITEM[1]), new THREE.MeshBasicMaterial({ map: texture, depthTest: false }));
            shown.renderOrder = 8;
            // from the top, clockwise
            const angle = 2 * Math.PI * k / links.length;
            shown.position.copy(middle).addScaledVector(right, Math.sin(angle) * MENU_AROUND[0]).addScaledVector(up, Math.cos(angle) * MENU_AROUND[1])
                .addScaledVector(towards, 0.02);
            shown.lookAt(shown.position.clone().add(towards));
            scene.add(shown);
            return { letter: link[0], shown };
        });
        menu = { id: node.id, items, chosen: -1 };
    }
}

/** Put the menu away; if it is to be [followed], the link the ray points at is. */
function closeMenu(followed: boolean) {
    const was = menu;
    menu = null;
    if (was !== null) {
        if (followed && was.chosen >= 0) tell?.(JSON.stringify({ type: "follow", id: was.id, letter: was.items[was.chosen].letter }));
        was.items.forEach((it) => {
            scene.remove(it.shown);
            it.shown.material.map?.dispose();
            it.shown.material.dispose();
            it.shown.geometry.dispose();
        });
    }
}

/** Which link of the menu the right controller's ray points at: that one is shown brighter. */
function pointInMenu() {
    const now = menu;
    const right = hands.find((it) => it.side === "left");
    if (now !== null && right !== undefined) {
        const from = new THREE.Vector3().setFromMatrixPosition(right.hand.matrixWorld);
        const ray = new THREE.Vector3(0, 0, -1).transformDirection(right.hand.matrixWorld);
        now.chosen = now.items.findIndex((it) => {
            const facing = new THREE.Vector3(0, 0, 1).transformDirection(it.shown.matrixWorld);
            const closing = ray.dot(facing);
            const along = closing === 0 ? -1 : it.shown.position.clone().sub(from).dot(facing) / closing;
            const on = it.shown.worldToLocal(from.clone().addScaledVector(ray, along));
            return along > 0 && Math.abs(on.x) <= MENU_ITEM[0] / 2 && Math.abs(on.y) <= MENU_ITEM[1] / 2;
        });
        now.items.forEach((it, k) => it.shown.material.color.set(k === now.chosen ? 0xffffff : 0x999999));
    }
}

/** The node the right controller points at, where on it (in the graph) and how far along the ray that is (metres); and a frame around it. */
let pointed: VrNode | null = null;
let pointedAt: { at: THREE.Vector3; along: number } | null = null;
/**
 * The node the trigger holds: how far along the ray, where on it it was taken (from its middle), where its middle was then, and
 * whether it has been moved since, which is what tells a node that is put somewhere from one that is clicked.
 */
let held: { id: string; along: number; grip: THREE.Vector3; from: THREE.Vector3; moved: boolean } | null = null;
/** A node held is moved once it is this far from where it was, in the graph's units. */
const MOVED_FROM = 8;
/** A node let go of is shown where it was put for this long: until the window says so too. */
const KEPT_AFTER_MS = 800;
/** The frame: a band around the node, [FRAME_WIDTH] of the graph's units wide and [FRAME_AWAY] from it; its corners, the outer four and then the inner four. */
const FRAME_WIDTH = 2;
const FRAME_AWAY = 3;

/** Find what the controller [right] points at (either of them: the name is of the one that also holds nodes): of the nodes its ray goes through, the nearest. The ray is drawn up to it. */
function pointWith(right: typeof hands[number]) {
    const frameAround = right.frame;
    let nearest: { node: VrNode; along: number } | null = null;
    let at: { at: THREE.Vector3; along: number } | null = null;
    if (right.side !== "none" && shown !== null) {
        // the ray in the graph's own space, where a node is a rectangle at its depth
        graph.updateMatrixWorld();
        right.hand.updateMatrixWorld();
        const into = graph.matrixWorld.clone().invert();
        const from = new THREE.Vector3().setFromMatrixPosition(right.hand.matrixWorld).applyMatrix4(into).sub(offset);
        const towards = new THREE.Vector3(0, 0, -1).transformDirection(right.hand.matrixWorld).add(new THREE.Vector3().setFromMatrixPosition(right.hand.matrixWorld))
            .applyMatrix4(into).sub(offset).sub(from);
        const holding = right.side === "right" ? held : null;
        const axes = laid?.axes;
        if (holding !== null) {
            // the node held stays as far along the ray as it was taken, and goes where the ray goes
            const middle = from.clone().addScaledVector(towards, holding.along).sub(holding.grip);
            // on the walls a node is not moved: where it is there is not where it is in the graph
            holding.moved = !inRoom && (holding.moved || middle.distanceTo(holding.from) > MOVED_FROM);
            if (holding.moved && received !== null) {
                placed.set(holding.id, { at: { x: middle.x, y: middle.y, z: middle.z }, until: Infinity });
                show(received);
            }
            const node = shown.nodes.find((n) => n.id === holding.id);
            if (node !== undefined) nearest = { node, along: holding.along };
        } else if (axes !== undefined) shown.nodes.forEach((n, i) => {
            // where the ray meets the plane the node is turned into ([face]), and whether that is on the node. [along] in metres: [towards] is a metre of the ray
            const [rx, ry, rz, ux, uy, uz, fx, fy, fz] = axes.subarray(i * 9, i * 9 + 9);
            const closing = towards.x * fx + towards.y * fy + towards.z * fz;
            const along = closing === 0 ? -1 : ((n.x - from.x) * fx + (n.y - from.y) * fy + (n.z - from.z) * fz) / closing;
            const [dx, dy, dz] = [from.x + towards.x * along - n.x, from.y + towards.y * along - n.y, from.z + towards.z * along - n.z];
            const hit = along > 0 && (nearest === null || along < nearest.along) &&
                Math.abs(dx * rx + dy * ry + dz * rz) <= n.width / 2 && Math.abs(dx * ux + dy * uy + dz * uz) <= n.height / 2;
            if (hit) nearest = { node: n, along };
        });
        const hit = nearest as { node: VrNode; along: number } | null;
        at = hit === null ? null : { at: from.clone().addScaledVector(towards, hit.along), along: hit.along };
    }
    const found = nearest as { node: VrNode; along: number } | null;
    right.pointed = found?.node ?? null;
    if (right.side === "right") {
        pointed = right.pointed;
        pointedAt = found === null ? null : at;
    }
    const i = found === null ? undefined : laid?.index.get(found.node);
    frameAround.visible = found !== null && i !== undefined;
    if (found !== null && laid !== null && i !== undefined) {
        // turned as the node is
        const n = found.node;
        const axes = laid.axes;
        const corners = frameAround.geometry.getAttribute("position") as THREE.BufferAttribute;
        [FRAME_AWAY + FRAME_WIDTH, FRAME_AWAY].forEach((away, ring) => {
            [[-1, -1], [1, -1], [1, 1], [-1, 1]].forEach(([sx, sy], k) => {
                const a = sx * (n.width / 2 + away);
                const c = sy * (n.height / 2 + away);
                corners.setXYZ(ring * 4 + k, n.x + axes[i * 9] * a + axes[i * 9 + 3] * c, n.y + axes[i * 9 + 1] * a + axes[i * 9 + 4] * c, n.z + axes[i * 9 + 2] * a + axes[i * 9 + 5] * c);
            });
        });
        corners.needsUpdate = true;
        frameAround.position.copy(offset);
    }
    right.ray.scale.z = found !== null ? found.along : RAY_LENGTH;
    right.ray.material.color.set(right.side === "right" && shown?.exploring ? RAY_EXPLORING : RAY);
}

function point() {
    // with no right controller there is nothing it points at
    if (!hands.some((it) => it.side === "right")) {
        pointed = null;
        pointedAt = null;
    }
    hands.forEach(pointWith);
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
        // of the right controller, where those two buttons are A and B: A forward again, B back to where a link was followed from
        const a = it.side === "right" && pad?.buttons[BUTTON_X]?.pressed === true;
        const b = it.side === "right" && pad?.buttons[BUTTON_Y]?.pressed === true;
        if (a && !it.a) tell?.(JSON.stringify({ type: "jump", back: false }));
        if (b && !it.b) tell?.(JSON.stringify({ type: "jump", back: true }));
        it.a = a;
        it.b = b;
    });
}

// --- in an ordinary browser: the mouse turns the graph, the wheel brings it nearer ---

/**
 * Out of a headset, with the graph on the walls: the walls as lines (in a headset the room itself is seen), and the eyes
 * go around the middle of the room with the mouse, at first looking down into it from behind where one would stand.
 */
const WALL_HEIGHT = 2.5;
const wallsShown = new THREE.LineSegments(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: ROOM_COLOUR }));
wallsShown.frustumCulled = false;
wallsShown.visible = false;
scene.add(wallsShown);
const around = { turned: 0.35, raised: 0.5, away: 9, middle: new THREE.Vector3(0, 1.2, 0) };

function showWalls(walls: number[]) {
    const lines: number[] = [];
    const xs: number[] = [];
    const zs: number[] = [];
    for (let i = 0; i + 3 < walls.length; i += 4) {
        const [x1, z1, x2, z2] = walls.slice(i, i + 4);
        lines.push(x1, 0, z1, x2, 0, z2, x2, 0, z2, x2, WALL_HEIGHT, z2, x2, WALL_HEIGHT, z2, x1, WALL_HEIGHT, z1, x1, WALL_HEIGHT, z1, x1, 0, z1);
        xs.push(x1, x2);
        zs.push(z1, z2);
    }
    wallsShown.geometry.setAttribute("position", new THREE.Float32BufferAttribute(lines, 3));
    if (xs.length > 0) around.middle.set((Math.min(...xs) + Math.max(...xs)) / 2, 1.2, (Math.min(...zs) + Math.max(...zs)) / 2);
}

function lookAround() {
    const level = Math.cos(around.raised) * around.away;
    camera.position.set(around.middle.x + Math.sin(around.turned) * level, around.middle.y + Math.sin(around.raised) * around.away, around.middle.z + Math.cos(around.turned) * level);
    camera.lookAt(around.middle);
}

canvas.addEventListener("pointermove", (e) => {
    if (e.buttons !== 0 && !renderer.xr.isPresenting && inRoom) {
        around.turned -= e.movementX * 0.005;
        around.raised = Math.min(1.5, Math.max(-0.2, around.raised + e.movementY * 0.005));
    } else if (e.buttons !== 0 && !renderer.xr.isPresenting) {
        graph.rotation.y += e.movementX * 0.005;
        graph.rotation.x += e.movementY * 0.005;
    }
});
canvas.addEventListener("wheel", (e) => {
    if (!renderer.xr.isPresenting && inRoom) around.away = Math.min(40, Math.max(0.5, around.away * (e.deltaY < 0 ? 1 / 1.1 : 1.1)));
    else if (!renderer.xr.isPresenting) graph.scale.multiplyScalar(e.deltaY < 0 ? 1.1 : 1 / 1.1);
});

/** Where one stood and looked when the window was last told, and when that was: it is told again after a step or a turn, not more often than [REPORT_MS]. */
let stood: { at: THREE.Vector2; looks: THREE.Vector2; when: number } | null = null;
const STEPPED = 0.25;
const TURNED = Math.cos(15 * Math.PI / 180);

/** Tell the window where one stands in the room and which way one looks, seen from above: a graph is put on the wall one looks at. */
function tellStanding() {
    const eyes = renderer.xr.getCamera();
    const at = new THREE.Vector3().setFromMatrixPosition(eyes.matrixWorld);
    const ahead = new THREE.Vector3(0, 0, -1).transformDirection(eyes.matrixWorld);
    const looks = new THREE.Vector2(ahead.x, ahead.z);
    const now = performance.now();
    // looking straight up or down one looks at no wall
    if (tell !== null && looks.length() > 0.2 && (stood === null || now - stood.when > REPORT_MS)) {
        looks.normalize();
        const here = new THREE.Vector2(at.x, at.z);
        if (stood === null || stood.at.distanceTo(here) > STEPPED || stood.looks.dot(looks) < TURNED) {
            const round = (n: number) => Math.round(n * 100) / 100;
            tell(JSON.stringify({ type: "stand", x: round(here.x), z: round(here.y), ax: round(looks.x), az: round(looks.y) }));
            stood = { at: here, looks, when: now };
        }
    }
}

renderer.setAnimationLoop((_time, frame) => {
    if (frame) showScanned(frame);
    if (renderer.xr.isPresenting) tellStanding();
    else if (inRoom) lookAround();
    wallsShown.visible = inRoom && !renderer.xr.isPresenting;
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
    face();
    point();
    pointInMenu();
    placeStatus();
    renderer.render(scene, camera);
});

connect();
