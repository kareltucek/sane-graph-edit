// The graph drawn with depth: every node a rectangle in space that faces the viewer, with a picture of the node on it,
// and the edges strips between them. The perspective is the graphics card's (three.js on WebGL), not worked out here.

import * as THREE from "three";

/** A node of the scene: where the view sees it (x across, y down, z away) and how big it is, in the graph's units. */
export interface SpaceNode {
    id: string;
    x: number;
    y: number;
    z: number;
    width: number;
    height: number;
    shape: string;
}

export interface SpaceScene<N extends SpaceNode> {
    nodes: N[];
    /** Indices into [nodes]. */
    edges: { from: number; to: number }[];
    /** The viewer is [distance] in front of the plane at [depth], before its point [middleX], [middleY]; a unit of that plane is [scale] pixels. */
    middleX: number;
    middleY: number;
    depth: number;
    distance: number;
    scale: number;
}

/** A picture of a node: [pixelsPerUnit] pixels to a unit of the graph, with [margin] units around the node. */
export interface NodePicture {
    image: OffscreenCanvas;
    pixelsPerUnit: number;
    margin: number;
}

/** What is kept for a node between frames. */
interface Kept {
    mesh: THREE.Mesh<THREE.PlaneGeometry, THREE.MeshBasicMaterial>;
    /** What the picture on it is of, and how fine it is. */
    key: string;
    pixelsPerUnit: number;
    /** The picture's size in pixels, and where in it the middle of the node is. */
    pixelWidth: number;
    pixelHeight: number;
    middleX: number;
    middleY: number;
    frame: number;
}

/** What is nearer the viewer than this many times its size is not drawn: as the client does not find it under the pointer either. */
const MAX_PERSPECTIVE = 50;
/**
 * A node seen at more than this part of its size at zoom 1 gets a
 * picture made for exactly the size it is seen at, pixel for pixel,
 * so that its text is sharp; a smaller one is shown from the picture
 * made for zoom 1, scaled down.
 */
const EXACT_FROM = 0.5;
/** The view is still when it has not moved for this long. */
const SETTLE_MS = 150;
/** Two sizes no further apart than this are the same one. */
const SAME = 1.001;
/** No picture is wider or higher than this many pixels. */
const MAX_PICTURE_SIDE = 4096;
/** At most this many pictures are made for one frame: the rest keep the one they have until a later frame. */
const PICTURES_PER_FRAME = 12;
const HEAD_SEGMENTS = 8;
const HEAD_COS = Array.from({ length: HEAD_SEGMENTS + 1 }, (_, i) => Math.cos(i / HEAD_SEGMENTS * 2 * Math.PI));
const HEAD_SIN = Array.from({ length: HEAD_SEGMENTS + 1 }, (_, i) => Math.sin(i / HEAD_SEGMENTS * 2 * Math.PI));

/**
 * Coloured triangles, all drawn at once: the edges, and the nodes
 * that are too small on the screen for their text to show. Filled
 * anew for every frame into arrays that are kept, so that nothing
 * is made for a frame but what the graphics card is given.
 */
class Triangles {
    readonly mesh: THREE.Mesh<THREE.BufferGeometry, THREE.MeshBasicMaterial>;
    private positions = new Float32Array(3 * 1024);
    private colours = new Float32Array(4 * 1024);
    private indices = new Uint32Array(6 * 1024);
    private vertices = 0;
    private corners = 0;
    /** How many vertices and indices the geometry has room for. */
    private room = { vertices: 0, indices: 0 };

    constructor(renderOrder: number) {
        this.mesh = new THREE.Mesh(
            new THREE.BufferGeometry(),
            new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, depthTest: false, side: THREE.DoubleSide }),
        );
        this.mesh.renderOrder = renderOrder;
        this.mesh.frustumCulled = false;
    }

    begin() {
        this.vertices = 0;
        this.corners = 0;
    }

    /** A point where the view sees it (y down, z away), in a colour; its number. */
    vertex(x: number, y: number, z: number, r: number, g: number, b: number, alpha: number): number {
        if ((this.vertices + 1) * 3 > this.positions.length) {
            this.positions = grown(this.positions);
            this.colours = grown(this.colours);
        }
        const p = this.vertices * 3;
        this.positions[p] = x;
        this.positions[p + 1] = -y;
        this.positions[p + 2] = -z;
        const c = this.vertices * 4;
        this.colours[c] = r;
        this.colours[c + 1] = g;
        this.colours[c + 2] = b;
        this.colours[c + 3] = alpha;
        return this.vertices++;
    }

    triangle(a: number, b: number, c: number) {
        if (this.corners + 3 > this.indices.length) this.indices = grown(this.indices);
        this.indices[this.corners++] = a;
        this.indices[this.corners++] = b;
        this.indices[this.corners++] = c;
    }

    /** The four corners of a rectangle facing the viewer, at one depth. */
    rectangle(left: number, top: number, right: number, bottom: number, z: number, r: number, g: number, b: number) {
        const first = this.vertex(left, top, z, r, g, b, 1);
        this.vertex(right, top, z, r, g, b, 1);
        this.vertex(left, bottom, z, r, g, b, 1);
        this.vertex(right, bottom, z, r, g, b, 1);
        this.triangle(first, first + 1, first + 2);
        this.triangle(first + 1, first + 3, first + 2);
    }

    end() {
        const geometry = this.mesh.geometry;
        if (this.room.vertices < this.positions.length / 3 || this.room.indices < this.indices.length) {
            // the arrays grew: the graphics card is given new ones
            geometry.setAttribute("position", new THREE.BufferAttribute(this.positions, 3).setUsage(THREE.DynamicDrawUsage));
            geometry.setAttribute("color", new THREE.BufferAttribute(this.colours, 4).setUsage(THREE.DynamicDrawUsage));
            geometry.setIndex(new THREE.BufferAttribute(this.indices, 1).setUsage(THREE.DynamicDrawUsage));
            this.room = { vertices: this.positions.length / 3, indices: this.indices.length };
        } else {
            geometry.getAttribute("position").needsUpdate = true;
            geometry.getAttribute("color").needsUpdate = true;
            geometry.getIndex()!.needsUpdate = true;
        }
        geometry.setDrawRange(0, this.corners);
    }
}

function grown<T extends Float32Array | Uint32Array>(array: T): T {
    const bigger = new (array.constructor as { new(length: number): T })(array.length * 2);
    bigger.set(array);
    return bigger;
}

function rgb(colour: string): [number, number, number] {
    const c = new THREE.Color(colour);
    return [c.r, c.g, c.b];
}

export class SpaceRenderer<N extends SpaceNode> {
    private readonly renderer: THREE.WebGLRenderer;
    private readonly scene = new THREE.Scene();
    private readonly camera = new THREE.PerspectiveCamera();
    private readonly kept = new Map<string, Kept>();
    // Before the nodes with pictures, which are drawn from the furthest to the nearest: an edge is never over a node,
    // and the nodes too small for a picture, which are the far ones, are under the others.
    private readonly edges = new Triangles(-2);
    private readonly small = new Triangles(-1);
    private frame = 0;
    /** For tests: how many milliseconds the parts of the last drawing took, how many pictures it made and how many draw calls. */
    spent = { nodes: 0, edges: 0, render: 0, made: 0, calls: 0 };
    /** Where the view looked from when it was last drawn, and when that last changed. */
    private lookedFrom = "";
    private movedAt = 0;

    constructor(private readonly canvas: HTMLCanvasElement) {
        this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
        this.scene.add(this.edges.mesh);
        this.scene.add(this.small.mesh);
    }

    /**
     * Draw [space] on the canvas, [width] by [height] of the page's
     * pixels, [dpr] of the screen's to each, which makes it
     * [pixelWidth] by [pixelHeight] of those. [keyOf] tells what a
     * node's picture depends on, [paint] makes one. An edge is in
     * [ink], [radius] the size of its head, and fainter the longer
     * it is on the screen ([fade]: how much is taken away at most).
     * [flatOf] tells of a node seen so small that its text does not
     * show (given how many pixels a unit of it is seen with) how to
     * draw it without a picture: filled, with an outline; null for
     * one that gets its picture.
     * True if some pictures are still to be made: draw again.
     */
    draw(
        space: SpaceScene<N>,
        width: number,
        height: number,
        pixelWidth: number,
        pixelHeight: number,
        dpr: number,
        background: string,
        ink: string,
        radius: number,
        fade: number,
        keyOf: (n: N) => string,
        paint: (n: N, pixelsPerUnit: number) => NodePicture,
        flatOf: (n: N, pixelsPerUnit: number) => { fill: string; ink: string; outline: number } | null,
    ): boolean {
        this.frame++;
        // one pixel of the canvas for each of the screen's, as the flat canvas has it, or all of it is resampled
        this.renderer.setPixelRatio(1);
        this.renderer.setSize(pixelWidth, pixelHeight, false);
        this.renderer.setClearColor(background);
        // The client's own picture of things, which it finds nodes under the pointer by: a pinhole in front of the middle of the
        // screen, [distance] from the plane. Here y is up and z towards the viewer, so both are turned around.
        const focal = space.distance * space.scale;
        this.camera.fov = 2 * Math.atan(height / 2 / focal) * 180 / Math.PI;
        this.camera.aspect = width / height;
        const near = space.distance / MAX_PERSPECTIVE;
        this.camera.near = near;
        this.camera.far = space.distance * 1e4;
        this.camera.position.set(space.middleX, -space.middleY, space.distance - space.depth);
        this.camera.updateProjectionMatrix();

        /** How far in front of the viewer [n] is. */
        const away = (n: SpaceNode) => space.distance + n.z - space.depth;
        // While the view moves (pan, zoom, turn) the pictures there are are shown, whatever size they were made for;
        // those of the right size are made once it has been still for a moment.
        const from = [space.scale, space.middleX, space.middleY, space.depth, space.distance, space.nodes.reduce((sum, n) => sum + n.z, 0)].join(" ");
        const now = performance.now();
        if (from !== this.lookedFrom) {
            this.lookedFrom = from;
            this.movedAt = now;
        }
        const moving = now - this.movedAt < SETTLE_MS;
        let made = 0;
        let pending = false;
        // the furthest first: what is nearer is drawn over it
        const order = space.nodes.map((n, i) => i).filter((i) => away(space.nodes[i]) > near).sort((a, b) => space.nodes[b].z - space.nodes[a].z);
        /** As many of the screen's pixels to a unit as [n] is seen with. */
        const seenWith = (n: SpaceNode) => space.distance / away(n) * space.scale * dpr;
        this.small.begin();
        const pictured = order.filter((index) => {
            const n = space.nodes[index];
            const seen = seenWith(n);
            // in pixels from the middle of the screen
            const x = (n.x - space.middleX) * seen;
            const y = (n.y - space.middleY) * seen;
            const inView = Math.abs(x) - n.width * seen / 2 < pixelWidth / 2 + seen * 4 && Math.abs(y) - n.height * seen / 2 < pixelHeight / 2 + seen * 4;
            const flat = inView ? flatOf(n, seen) : null;
            if (flat !== null) {
                // too small for its text to show: an outline and a filling, drawn with all the others like it
                const hw = n.width / 2;
                const hh = n.height / 2;
                const inset = Math.min(flat.outline, hw, hh);
                this.small.rectangle(n.x - hw, n.y - hh, n.x + hw, n.y + hh, n.z, ...rgb(flat.ink));
                this.small.rectangle(n.x - hw + inset, n.y - hh + inset, n.x + hw - inset, n.y + hh - inset, n.z, ...rgb(flat.fill));
            } else if (!inView) {
                // not seen: nothing is worked out for it, and the picture it has is kept for when it is seen again
                const kept = this.kept.get(n.id);
                if (kept !== undefined) {
                    kept.frame = this.frame;
                    kept.mesh.visible = false;
                }
            }
            return inView && flat === null;
        });
        this.small.end();
        pictured.forEach((index, rank) => {
            const n = space.nodes[index];
            // as many pixels to a unit as the node is seen with, and as it would be at zoom 1
            const finest = MAX_PICTURE_SIDE / Math.max(n.width, n.height, 1);
            const seen = seenWith(n);
            const whole = Math.min(dpr, finest);
            const exact = seen > whole * EXACT_FROM && seen <= finest;
            const wanted = exact ? seen : whole;
            const key = keyOf(n);
            let kept = this.kept.get(n.id);
            const changed = kept === undefined || kept.key !== key;
            const stale = changed || kept!.pixelsPerUnit > wanted * SAME || kept!.pixelsPerUnit < wanted / SAME;
            if (stale && (kept === undefined || (made < PICTURES_PER_FRAME && (changed || !moving)))) {
                made++;
                const picture = paint(n, wanted);
                const texture = new THREE.CanvasTexture(picture.image);
                texture.colorSpace = THREE.SRGBColorSpace;
                if (exact) {
                    // shown pixel for pixel: nothing to scale
                    texture.generateMipmaps = false;
                    texture.minFilter = THREE.LinearFilter;
                } else texture.anisotropy = this.renderer.capabilities.getMaxAnisotropy();
                const sizes = {
                    pixelsPerUnit: wanted,
                    pixelWidth: picture.image.width,
                    pixelHeight: picture.image.height,
                    middleX: (n.width / 2 + picture.margin) * wanted,
                    middleY: (n.height / 2 + picture.margin) * wanted,
                };
                if (kept === undefined) {
                    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: texture, transparent: true, depthTest: false }));
                    kept = { mesh, key, frame: this.frame, ...sizes };
                    this.kept.set(n.id, kept);
                    this.scene.add(mesh);
                } else {
                    kept.mesh.material.map?.dispose();
                    kept.mesh.material.map = texture;
                    Object.assign(kept, { key, ...sizes });
                }
                // as big as the picture is, which is a little more than the node: whole pixels
                kept.mesh.scale.set(kept.pixelWidth / wanted, kept.pixelHeight / wanted, 1);
            } else if (stale) pending = true;
            const it = kept!;
            it.frame = this.frame;
            it.mesh.visible = true;
            // the middle of the picture, in units from the middle of the node: across and down
            let shiftX = (it.pixelWidth / 2 - it.middleX) / it.pixelsPerUnit;
            let shiftY = (it.pixelHeight / 2 - it.middleY) / it.pixelsPerUnit;
            if (it.pixelsPerUnit <= seen * SAME && it.pixelsPerUnit >= seen / SAME) {
                // pixel for pixel: the picture's corner on a corner of a pixel, or its text is blurred
                const left = pixelWidth / 2 + (n.x - space.middleX) * seen - it.middleX;
                const top = pixelHeight / 2 + (n.y - space.middleY) * seen - it.middleY;
                shiftX += (Math.round(left) - left) / seen;
                shiftY += (Math.round(top) - top) / seen;
            }
            it.mesh.position.set(n.x + shiftX, -(n.y + shiftY), -n.z);
            it.mesh.renderOrder = rank;
        });
        // what is no longer there, or behind the viewer
        this.kept.forEach((it, id) => {
            if (it.frame !== this.frame) {
                this.scene.remove(it.mesh);
                it.mesh.material.map?.dispose();
                it.mesh.material.dispose();
                it.mesh.geometry.dispose();
                this.kept.delete(id);
            }
        });
        const t1 = performance.now();
        this.strips(space, width, height, ink, radius, fade);
        const t2 = performance.now();
        this.renderer.render(this.scene, this.camera);
        const t3 = performance.now();
        this.spent = { nodes: t1 - now, edges: t2 - t1, render: t3 - t2, made, calls: this.renderer.info.render.calls };
        return pending;
    }

    /** The edges, as strips a unit wide from border to border of their nodes, each with a disc for a head. */
    private strips(space: SpaceScene<N>, width: number, height: number, ink: string, radius: number, fade: number) {
        const [red, green, blue] = rgb(ink);
        const edges = this.edges;
        edges.begin();
        const near = space.distance / MAX_PERSPECTIVE;
        /** Where on the screen a point is seen, in pixels from the middle; what is behind the viewer as if just in front of him. */
        const seen = (x: number, y: number, z: number) => {
            const p = space.distance / Math.max(space.distance + z - space.depth, near);
            return { x: (x - space.middleX) * p * space.scale, y: (y - space.middleY) * p * space.scale };
        };
        /** How far from the middle of [n] its border is, going the way of [dx], [dy]. */
        const reach = (n: SpaceNode, dx: number, dy: number) =>
            n.shape === "oval"
                ? 1 / Math.hypot(dx / (n.width / 2), dy / (n.height / 2))
                : Math.min(dx === 0 ? Infinity : n.width / 2 / Math.abs(dx), dy === 0 ? Infinity : n.height / 2 / Math.abs(dy));
        const from = Math.min(width, height) * 0.125;
        const to = Math.max(width, height) * 0.5;
        const vertex = (x: number, y: number, z: number, alpha: number) => edges.vertex(x, y, z, red, green, blue, alpha);
        // an edge with both ends off the same side of the screen is not seen
        const halfWidth = width / 2 + 50;
        const halfHeight = height / 2 + 50;
        space.edges.forEach((e) => {
            const a = space.nodes[e.from];
            const b = space.nodes[e.to];
            const sa = seen(a.x, a.y, a.z);
            const sb = seen(b.x, b.y, b.z);
            const length = Math.hypot(sb.x - sa.x, sb.y - sa.y);
            const off = Math.min(sa.x, sb.x) > halfWidth || Math.max(sa.x, sb.x) < -halfWidth || Math.min(sa.y, sb.y) > halfHeight || Math.max(sa.y, sb.y) < -halfHeight;
            if (length > 0 && !off && (space.distance + a.z - space.depth > near || space.distance + b.z - space.depth > near)) {
                // the way the edge goes on the screen; the nodes face the screen, so that is the way it leaves them
                const dx = (sb.x - sa.x) / length;
                const dy = (sb.y - sa.y) / length;
                const out = reach(a, dx, dy);
                const into = reach(b, dx, dy) + radius;
                const x1 = a.x + dx * out;
                const y1 = a.y + dy * out;
                const x2 = b.x - dx * into;
                const y2 = b.y - dy * into;
                const alpha = 1 - (to > from ? Math.min(1, Math.max(0, (length - from) / (to - from))) : 0) * fade;
                // half a unit to either side, across the way it goes
                const px = -dy / 2;
                const py = dx / 2;
                const first = vertex(x1 + px, y1 + py, a.z, alpha);
                vertex(x1 - px, y1 - py, a.z, alpha);
                vertex(x2 + px, y2 + py, b.z, alpha);
                vertex(x2 - px, y2 - py, b.z, alpha);
                edges.triangle(first, first + 1, first + 2);
                edges.triangle(first + 1, first + 3, first + 2);
                const middle = vertex(x2, y2, b.z, alpha);
                for (let i = 0; i <= HEAD_SEGMENTS; i++) {
                    vertex(x2 + HEAD_COS[i] * radius, y2 + HEAD_SIN[i] * radius, b.z, alpha);
                    if (i > 0) edges.triangle(middle, middle + i, middle + i + 1);
                }
            }
        });
        edges.end();
    }
}
