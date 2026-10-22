// The graph drawn with depth: every node a rectangle in space that faces the viewer, with a picture of the node on it,
// and the edges strips between them. The perspective is the graphics card's (three.js on WebGL), not worked out here.

import * as THREE from "three";
import { ROOM_COLOUR } from "./room";

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

/** A system of nodes of [level] (`o` on the whole graph, `:suns`): a ball [radius] around its middle; of level 0 a node's, [sun] if it is the sun of one of the lowest. */
export interface Shell {
    x: number;
    y: number;
    z: number;
    radius: number;
    level: number;
    sun: boolean;
    /** How far it reaches at most, laid straight: a ring around it. */
    reach?: number | null;
}

export interface SpaceScene<N extends SpaceNode> {
    nodes: N[];
    /** None but while `o` settles the whole graph. */
    shells?: Shell[];
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
 * Once the view is still, a node seen at more than this part of its
 * size at zoom 1 gets a picture made for exactly the size it is seen
 * at, pixel for pixel, so that its text is sharp; a smaller one is
 * shown from its picture in the atlas, made for zoom 1, scaled down.
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
/**
 * What is in front of what is the graphics card's business (its
 * depth buffer), not the order things are drawn in: nodes drawn
 * many at once from different pages cannot be drawn from the
 * furthest to the nearest.
 *
 * A picture of a node hides what is behind it where it is not clear
 * (its margin and the corners of its outline are), its edge smoothed.
 * Of two nodes at one depth the one drawn later is on top.
 */
const PICTURE = { alphaTest: 0.5, alphaToCoverage: true } as const;
/**
 * Edges are drawn after the nodes, faint ones letting through what
 * is behind them. One is hidden by a node in front of it and also by
 * one at its own depth: in a flat graph an edge is under the nodes.
 */
const EDGES = { vertexColors: true, transparent: true, depthWrite: false, depthFunc: THREE.LessDepth, side: THREE.DoubleSide } as const;
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

    /** [solid]: nodes, which hide what is behind them; otherwise edges, drawn over what is behind them and under what is not. */
    constructor(renderOrder: number, solid: boolean) {
        this.mesh = new THREE.Mesh(
            new THREE.BufferGeometry(),
            new THREE.MeshBasicMaterial(solid ? { vertexColors: true, side: THREE.DoubleSide } : EDGES),
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

/** One side of a page of the atlas, in pixels. */
const PAGE = 2048;
/** The rows of a page are whole multiples of this many pixels high. */
const ROW_STEP = 8;
/** A picture wider or higher than this many pixels is not put on a page: it would take too much of it. */
const MAX_ON_PAGE = 1024;
/** While pictures are being put on a page, the graphics card is given the page no more often than this. */
const PAGE_UPLOAD_MS = 300;
/** Pictures are made in the background for this many milliseconds at a time. */
const FILL_SLICE_MS = 10;
/** Pictures are made for the atlas for at most this many milliseconds of a frame. */
const ATLAS_BUDGET_MS = 6;

/** Where the picture of a node is in the atlas, and what it is a picture of. */
interface Slot {
    page: Page;
    x: number;
    y: number;
    width: number;
    height: number;
    key: string;
    /** As [Kept]: how fine the picture is and where in it the middle of the node is. */
    pixelsPerUnit: number;
    middleX: number;
    middleY: number;
}

/**
 * A page of the atlas: many small pictures of nodes in one big
 * picture, so that the nodes shown small, which on a big graph are
 * nearly all of them, are drawn at once and not one by one.
 */
class Page {
    readonly canvas = new OffscreenCanvas(PAGE, PAGE);
    /** Goes up whenever a picture on the page changes: for who else shows the page, to know it has. */
    version = 0;
    readonly ctx = this.canvas.getContext("2d")!;
    readonly texture = new THREE.CanvasTexture(this.canvas);
    readonly mesh: THREE.Mesh<THREE.BufferGeometry, THREE.MeshBasicMaterial>;
    private positions = new Float32Array(3 * 4 * 256);
    private places = new Float32Array(2 * 4 * 256);
    private indices = new Uint32Array(6 * 256);
    private quads = 0;
    private room = 0;
    /** Pictures are put in rows, each for pictures of about one height: how high a row is, and where its next picture goes. */
    private readonly rows: { y: number; height: number; nextX: number }[] = [];
    private nextY = 0;
    /** The page has pictures the graphics card has not been given yet; when it last was given the page. */
    dirty = false;
    uploadedAt = 0;

    constructor(anisotropy: number) {
        this.texture.colorSpace = THREE.SRGBColorSpace;
        this.texture.anisotropy = anisotropy;
        this.mesh = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ map: this.texture, ...PICTURE }));
        this.mesh.renderOrder = -1;
        this.mesh.frustumCulled = false;
    }

    /** Room for a picture of [width] by [height] pixels, a pixel free around it; null when the page is full. */
    place(width: number, height: number): { x: number; y: number } | null {
        // a row of pictures no more than an eighth higher than this one: little is left free above it
        const high = Math.ceil(height / ROW_STEP) * ROW_STEP;
        let row = this.rows.find((it) => it.height === high && it.nextX + width + 1 <= PAGE);
        if (row === undefined && this.nextY + high + 1 <= PAGE && width + 1 <= PAGE) {
            row = { y: this.nextY, height: high, nextX: 0 };
            this.rows.push(row);
            this.nextY += high + 1;
        }
        const at = row === undefined ? null : { x: row.nextX, y: row.y };
        if (row !== undefined) row.nextX += width + 1;
        return at;
    }

    begin() {
        this.quads = 0;
    }

    /** The picture in [slot], shown with its middle [shiftX], [shiftY] from x, y (y down), z away. */
    quad(slot: Slot, x: number, y: number, z: number) {
        if ((this.quads + 1) * 12 > this.positions.length) {
            this.positions = grown(this.positions);
            this.places = grown(this.places);
            this.indices = grown(this.indices);
        }
        const left = x - slot.middleX / slot.pixelsPerUnit;
        const top = y - slot.middleY / slot.pixelsPerUnit;
        const right = left + slot.width / slot.pixelsPerUnit;
        const bottom = top + slot.height / slot.pixelsPerUnit;
        // the picture's own y is down, the texture's up
        const u1 = slot.x / PAGE;
        const u2 = (slot.x + slot.width) / PAGE;
        const v1 = 1 - slot.y / PAGE;
        const v2 = 1 - (slot.y + slot.height) / PAGE;
        this.positions.set([left, -top, -z, right, -top, -z, left, -bottom, -z, right, -bottom, -z], this.quads * 12);
        this.places.set([u1, v1, u2, v1, u1, v2, u2, v2], this.quads * 8);
        const first = this.quads * 4;
        this.indices.set([first, first + 2, first + 1, first + 1, first + 2, first + 3], this.quads * 6);
        this.quads++;
    }

    end() {
        const geometry = this.mesh.geometry;
        if (this.room < this.indices.length) {
            geometry.setAttribute("position", new THREE.BufferAttribute(this.positions, 3).setUsage(THREE.DynamicDrawUsage));
            geometry.setAttribute("uv", new THREE.BufferAttribute(this.places, 2).setUsage(THREE.DynamicDrawUsage));
            geometry.setIndex(new THREE.BufferAttribute(this.indices, 1).setUsage(THREE.DynamicDrawUsage));
            this.room = this.indices.length;
        } else {
            geometry.getAttribute("position").needsUpdate = true;
            geometry.getAttribute("uv").needsUpdate = true;
            geometry.getIndex()!.needsUpdate = true;
        }
        geometry.setDrawRange(0, this.quads * 6);
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

/**
 * The corners of the selected nodes that have a size of their own, by which they are resized; made anew each frame, as
 * there are few.
 */
class Grips {
    private readonly geometry = new THREE.BufferGeometry();
    /** Squares of a size in pixels, however far the node is; what is in front of them hides them, as it hides the node. */
    readonly points = new THREE.Points(this.geometry, new THREE.PointsMaterial({ size: 1, sizeAttenuation: false, vertexColors: true }));
    private corners: number[] = [];
    private colours: number[] = [];

    /** A frame begins, its corners [pixels] wide. */
    begin(pixels: number) {
        this.corners = [];
        this.colours = [];
        this.points.material.size = pixels;
    }

    /** The corners of a rectangle around the middle [x], [y], half [hw] wide and [hh] high, at depth [z], in [ink]. */
    grip(x: number, y: number, hw: number, hh: number, z: number, ink: string) {
        const [r, g, b] = rgb(ink);
        // a little in front of the node, as a ring; here y is up and z towards the viewer
        const front = -z + 0.5;
        [[x - hw, y - hh], [x + hw, y - hh], [x - hw, y + hh], [x + hw, y + hh]].forEach(([cx, cy]) => {
            this.corners.push(cx, -cy, front);
            this.colours.push(r, g, b);
        });
    }

    end() {
        this.geometry.setAttribute("position", new THREE.Float32BufferAttribute(this.corners, 3));
        this.geometry.setAttribute("color", new THREE.Float32BufferAttribute(this.colours, 3));
        this.points.visible = this.corners.length > 0;
    }
}

/**
 * Rings around the selected nodes: lines, a pixel wide however far. One buffer for all, a slot of four segments for each
 * node that has a ring; each frame only a ring that changed (the node moved, was selected or no longer is) is written, and
 * only its slot is sent to the graphics card. A slot that is let go is drawn as nothing (all its ends at one point) and
 * given to the next node.
 */
class Rings {
    /** Points of a slot: four segments, two ends each. */
    private static readonly POINTS = 8;
    private capacity = 0;
    private positions = new Float32Array(0);
    private colours = new Float32Array(0);
    /** Each ringed node's slot and what is written there, compared field by field: nothing is made each frame. */
    private readonly slots = new Map<string, { slot: number; x: number; y: number; hw: number; hh: number; z: number; ink: string }>();
    private readonly free: number[] = [];
    private used = 0;
    /** The nodes ringed this frame. */
    private seen = new Set<string>();
    private readonly geometry = new THREE.BufferGeometry();
    /** Pixels a copy is moved by: right, down. */
    private static readonly SHIFTS = [[0, 0], [1, 0], [0, 1], [1, 1]];
    /**
     * The rings, and the same moved a pixel right, down and both, on the graphics card: two pixels wide, which a line never
     * is, and more than the one-pixel outline every node has. Four draws of one buffer, nothing more to send.
     */
    readonly lines = Rings.SHIFTS.map(() => new THREE.LineSegments(this.geometry, new THREE.ShaderMaterial({
        vertexColors: true,
        uniforms: { shift: { value: new THREE.Vector2() } },
        vertexShader: `
            uniform vec2 shift;
            varying vec3 tint;
            void main() {
                tint = color;
                gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
                gl_Position.xy += shift * gl_Position.w;
            }`,
        fragmentShader: `
            varying vec3 tint;
            void main() {
                gl_FragColor = vec4(tint, 1.0);
                // the colours are linear, as three's own materials have them; the screen's are not
                #include <colorspace_fragment>
            }`,
    })));

    /** How big a pixel is: the canvas is [width] by [height] of them. */
    pixels(width: number, height: number) {
        this.lines.forEach((line, i) => {
            const [x, y] = Rings.SHIFTS[i];
            // a pixel is 2 / width of the screen's -1 to 1 across
            (line.material.uniforms.shift.value as THREE.Vector2).set(2 * x / width, -2 * y / height);
        });
    }

    begin() {
        this.seen = new Set();
    }

    /** [id] is to be ringed this frame: a rectangle around the middle [x], [y], half [hw] wide and [hh] high, at depth [z], in [ink]. */
    ring(id: string, x: number, y: number, hw: number, hh: number, z: number, ink: string) {
        this.seen.add(id);
        const had = this.slots.get(id);
        const same = had !== undefined && had.x === x && had.y === y && had.hw === hw && had.hh === hh && had.z === z && had.ink === ink;
        if (!same) {
            const slot = had?.slot ?? this.take();
            this.slots.set(id, { slot, x, y, hw, hh, z, ink });
            const [r, g, b] = rgb(ink);
            const corners = [[x - hw, y - hh], [x + hw, y - hh], [x + hw, y + hh], [x - hw, y + hh]];
            // a little in front of the node, so that its picture does not cover the ring; here y is up and z towards the viewer
            const front = -z + 0.5;
            corners.forEach(([cx, cy], i) => {
                const [nx, ny] = corners[(i + 1) % 4];
                this.write(slot, i * 2, cx, -cy, front, r, g, b);
                this.write(slot, i * 2 + 1, nx, -ny, front, r, g, b);
            });
            this.changed(slot);
        }
    }

    /** The rings of nodes not ringed this frame are let go. */
    end() {
        this.slots.forEach(({ slot }, id) => {
            if (!this.seen.has(id)) {
                for (let i = 0; i < Rings.POINTS; i++) this.write(slot, i, 0, 0, 0, 0, 0, 0);
                this.changed(slot);
                this.slots.delete(id);
                this.free.push(slot);
            }
        });
        this.geometry.setDrawRange(0, this.used * Rings.POINTS);
        this.lines.forEach((line) => line.visible = this.slots.size > 0);
    }

    private take(): number {
        const reused = this.free.pop();
        if (reused !== undefined) return reused;
        if (this.used === this.capacity) this.grow();
        return this.used++;
    }

    /** Twice as many slots: a new buffer, all of it sent once. */
    private grow() {
        this.capacity = Math.max(64, this.capacity * 2);
        const positions = new Float32Array(this.capacity * Rings.POINTS * 3);
        const colours = new Float32Array(this.capacity * Rings.POINTS * 3);
        positions.set(this.positions);
        colours.set(this.colours);
        this.positions = positions;
        this.colours = colours;
        this.geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
        this.geometry.setAttribute("color", new THREE.BufferAttribute(colours, 3));
    }

    private write(slot: number, point: number, x: number, y: number, z: number, r: number, g: number, b: number) {
        const at = (slot * Rings.POINTS + point) * 3;
        this.positions[at] = x;
        this.positions[at + 1] = y;
        this.positions[at + 2] = z;
        this.colours[at] = r;
        this.colours[at + 1] = g;
        this.colours[at + 2] = b;
    }

    /** Only [slot] is sent to the graphics card. */
    private changed(slot: number) {
        const start = slot * Rings.POINTS * 3;
        ["position", "color"].forEach((name) => {
            const attribute = this.geometry.getAttribute(name) as THREE.BufferAttribute;
            attribute.addUpdateRange(start, Rings.POINTS * 3);
            attribute.needsUpdate = true;
        });
    }
}

/** How strong the lines of the shells are: they are seen through, not looked at. */
const SHELL_OPACITY = 0.6;

/** The hues of the shells, from the suns (level 0) to the top level: orange, through yellow, green and cyan, to blue; full saturation. The other nodes' (planets) are black. */
const SHELL_HUES = [30 / 360, 225 / 360] as const;

/** Their lightness on a dark background, and on a light one. */
const SHELL_LIGHTNESS = { dark: 0.6, light: 0.4 } as const;

/** On [background], the colour of a shell ([Shell]) among levels up to a top one: red, green and blue, 0 to 1. */
export function shellColours(background: string): (shell: Shell, top: number) => [number, number, number] {
    const bg = new THREE.Color(background);
    const lightness = 0.299 * bg.r + 0.587 * bg.g + 0.114 * bg.b > 0.5 ? SHELL_LIGHTNESS.light : SHELL_LIGHTNESS.dark;
    // full saturation: of the hue's three channels, as far below and above the lightness as it allows
    const spread = Math.min(lightness, 1 - lightness);
    const channel = (hue: number, n: number) => {
        const k = (n + hue * 12) % 12;
        return lightness - spread * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    };
    return (shell, top) => {
        const hue = SHELL_HUES[0] + (SHELL_HUES[1] - SHELL_HUES[0]) * (top > 0 ? shell.level / top : 0);
        return shell.level === 0 && !shell.sun ? [0, 0, 0] : [channel(hue, 0), channel(hue, 8), channel(hue, 4)];
    };
}

/** How many pieces a ring is drawn of. */
const RING_PIECES = 32;

/**
 * The shells ([Shell]), all in one set of lines, filled anew for every
 * frame; a node in front of one hides it: wire dodecahedra, and rings
 * of their reaches across the plane.
 */
class Shells {
    /** The edges of a dodecahedron whose corners are a unit from its middle: two ends of each, x, y and z after each other. */
    private static readonly SYSTEM = new THREE.EdgesGeometry(new THREE.DodecahedronGeometry(1, 0)).getAttribute("position").array as Float32Array;
    /** A ring of a unit's radius across the plane, as [SYSTEM]. */
    private static readonly RING = Float32Array.from(Array.from({ length: RING_PIECES }, (_, k) => [k, k + 1]
        .flatMap(j => [Math.cos(2 * Math.PI * j / RING_PIECES), Math.sin(2 * Math.PI * j / RING_PIECES), 0])).flat());
    readonly lines = new THREE.LineSegments(
        new THREE.BufferGeometry(),
        new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: SHELL_OPACITY, depthWrite: false }),
    );

    constructor() {
        this.lines.frustumCulled = false;
        this.lines.renderOrder = 1e9 + 2;
    }

    /** Kept between frames; given anew to the graphics card only when they grow. */
    private positions = new Float32Array(0);
    private colours = new Float32Array(0);

    draw(shells: Shell[], background: string) {
        this.lines.visible = shells.length > 0;
        // each a dodecahedron of its room and, if it has one, a ring of its reach
        const pieces = shells.flatMap(s => [{ s, unit: Shells.SYSTEM, radius: s.radius }]
            .concat(s.reach != null ? [{ s, unit: Shells.RING, radius: s.reach }] : []));
        const length = pieces.reduce((sum, p) => sum + p.unit.length, 0);
        const geometry = this.lines.geometry;
        if (length > this.positions.length) {
            this.positions = new Float32Array(2 * length);
            this.colours = new Float32Array(2 * length);
            geometry.setAttribute("position", new THREE.BufferAttribute(this.positions, 3).setUsage(THREE.DynamicDrawUsage));
            geometry.setAttribute("color", new THREE.BufferAttribute(this.colours, 3).setUsage(THREE.DynamicDrawUsage));
        } else if (shells.length > 0) {
            geometry.getAttribute("position").needsUpdate = true;
            geometry.getAttribute("color").needsUpdate = true;
        }
        const positions = this.positions;
        const colours = this.colours;
        const top = shells.reduce((most, s) => Math.max(most, s.level), 0);
        const colourOf = shellColours(background);
        pieces.reduce((at, { s, unit, radius }) => {
            const [r, g, b] = colourOf(s, top);
            for (let k = 0; k < unit.length; k += 3) {
                // here y is up and z towards the viewer
                positions[at + k] = s.x + unit[k] * radius;
                positions[at + k + 1] = -(s.y + unit[k + 1] * radius);
                positions[at + k + 2] = -(s.z + unit[k + 2] * radius);
                colours[at + k] = r;
                colours[at + k + 1] = g;
                colours[at + k + 2] = b;
            }
            return at + unit.length;
        }, 0);
        geometry.setDrawRange(0, length / 3);
    }
}

export class SpaceRenderer<N extends SpaceNode> {
    private readonly renderer: THREE.WebGLRenderer;
    private readonly scene = new THREE.Scene();
    private readonly camera = new THREE.PerspectiveCamera();
    private readonly kept = new Map<string, Kept>();
    // the edges after all the nodes ([EDGES]); the nodes that have no picture yet before those that have
    private readonly edges = new Triangles(1e9, false);
    private readonly small = new Triangles(-2, true);
    /** The pictures of the nodes, each at its size at zoom 1: all those of a page are drawn at once. */
    private readonly pages: Page[] = [];
    private readonly slots = new Map<string, Slot>();
    private frame = 0;
    /** The room the graph is in, as lines; drawn like anything else in space, so that nodes in front of a line hide it. */
    private readonly walls = new THREE.LineSegments(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: ROOM_COLOUR }));
    /** Rings around the selected nodes, drawn after them ([Rings]). */
    private readonly rings = new Rings();
    private readonly grips = new Grips();
    private readonly shells = new Shells();
    /** For trying how fast it is without them (`SGE_NO_EDGES`): whether edges are drawn. */
    edgesShown = true;
    /** For tests: how many milliseconds the parts of the last drawing took, how many pictures it made and how many draw calls. */
    spent = { nodes: 0, edges: 0, render: 0, made: 0, calls: 0 };
    /** Where the view looked from when it was last drawn, and when that last changed. */
    private lookedFrom = "";
    private movedAt = 0;

    /**
     * [onFilled]: some pictures were made in the background and the
     * graph is to be drawn again, so that the graphics card gets them
     * a page at a time and not all at once when they are first seen.
     */
    constructor(private readonly canvas: HTMLCanvasElement, private readonly onFilled: () => void) {
        this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
        this.scene.add(this.edges.mesh);
        this.scene.add(this.small.mesh);
        this.walls.frustumCulled = false;
        this.scene.add(this.walls);
        this.rings.lines.forEach((line) => {
            line.frustumCulled = false;
            line.renderOrder = 1e9 + 1;
            this.scene.add(line);
        });
        this.grips.points.frustumCulled = false;
        this.grips.points.renderOrder = 1e9 + 1;
        this.scene.add(this.grips.points);
        this.scene.add(this.shells.lines);
    }

    /** The lines to show of the room at the next drawing, as the view sees them: the two ends of each, x, y and z after each other. Null: none. */
    room(lines: Float64Array | null) {
        this.walls.visible = lines !== null;
        // here y is up and z towards the viewer
        if (lines !== null) this.walls.geometry.setAttribute("position", new THREE.Float32BufferAttribute(Array.from(lines, (v, i) => i % 3 === 0 ? v : -v), 3));
    }

    // --- filling the atlas in the background ---
    // Drawing makes the pictures of the nodes it shows. The rest are made here, a few milliseconds at a time between whatever
    // else the page has to do (there is one thread: a longer piece of work would hold up the pointer and the keys), so that
    // going somewhere else in the graph finds its pictures made. It starts when a graph is first drawn with depth.

    /** The nodes of the graph as it was last drawn, and how to make their pictures. */
    private toFill: { nodes: N[]; dpr: number; keyOf: (n: N) => string; paint: (n: N, pixelsPerUnit: number) => NodePicture; fit?: boolean } | null = null;

    /**
     * How fine the picture of [n] is on a page when pictures are [dpr] pixels to a unit: that, for one that is no bigger than
     * [MAX_ON_PAGE] so; null for a bigger one, which is not put on a page, unless [fit]: then as fine as a page has room for.
     */
    private static onPage(n: SpaceNode, dpr: number, fit: boolean): number | null {
        const side = Math.max(n.width, n.height);
        return side * dpr <= MAX_ON_PAGE ? dpr : fit ? Math.min(dpr, (PAGE - 8) / (side + 8)) : null;
    }
    /** Which graph the background work is for or was last done for: it is not done again for the same one. */
    private filledFor = "";
    private filling = false;
    /** Where in the nodes the work is. */
    private fillNext = 0;
    private toldAt = 0;

    /** Go through all the nodes again, from the first, making the pictures that are missing or out of date. */
    private refill() {
        this.fillNext = 0;
        if (!this.filling) {
            this.filling = true;
            setTimeout(() => this.fill(), 0);
        }
    }

    private fill() {
        const work = this.toFill;
        const start = performance.now();
        let made = false;
        while (work !== null && this.fillNext < work.nodes.length && performance.now() - start < FILL_SLICE_MS) {
            const n = work.nodes[this.fillNext++];
            const fine = SpaceRenderer.onPage(n, work.dpr, work.fit === true);
            if (fine !== null) {
                const key = work.keyOf(n);
                const slot = this.slots.get(n.id);
                if (slot === undefined || slot.key !== key) {
                    this.put(n, key, work.paint(n, fine), slot);
                    made = true;
                }
            }
        }
        this.filling = work !== null && this.fillNext < work.nodes.length;
        // the pages reach the graphics card when the graph is drawn
        if (made && (!this.filling || start - this.toldAt > PAGE_UPLOAD_MS)) {
            this.toldAt = start;
            this.onFilled();
        }
        if (this.filling) setTimeout(() => this.fill(), 0);
    }

    /**
     * Draw [space] on the canvas, [width] by [height] of the page's
     * pixels, [dpr] of the screen's to each, which makes it
     * [pixelWidth] by [pixelHeight] of those. [keyOf] tells what a
     * node's picture depends on, [paint] makes one. An edge is in
     * [ink], [radius] the size of its head, and fainter the longer
     * it is on the screen ([fade]: how much is taken away at most).
     * [flatOf] tells how to draw a node (seen with that many pixels
     * to a unit) while its picture is not made yet: filled, with an
     * outline.
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
        flatOf: (n: N, pixelsPerUnit: number) => { fill: string; ink: string; outline: number },
        /** Whether [n] is to be ringed (it is selected), and in what colour. */
        ringed: (n: N) => boolean,
        inkOf: (n: N) => string,
        /** Whether the corners [n] is resized by are shown, [gripPixels] wide. */
        gripped: (n: N) => boolean,
        gripPixels: number,
    ): boolean {
        this.rings.begin();
        this.grips.begin(gripPixels);
        this.rings.pixels(pixelWidth, pixelHeight);
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
        // the pictures of the nodes that are not shown now are made in the background
        this.toFill = { nodes: space.nodes, dpr, keyOf, paint };
        const graph = `${space.nodes.length} ${space.nodes[0]?.id} ${space.nodes[space.nodes.length - 1]?.id} ${dpr}`;
        if (graph !== this.filledFor) {
            this.filledFor = graph;
            this.refill();
        }
        /** More pictures for the atlas were out of date than this frame had time to make: the background work makes them. */
        let behind = false;
        this.small.begin();
        this.pages.forEach((page) => page.begin());
        /** Milliseconds of this frame spent making pictures for the atlas. */
        let painting = 0;
        // Every node has a picture in the atlas, made for zoom 1, and can always be shown from that: while the view moves all are.
        // Once it is still, a node seen at more than half that size gets a picture of its own, made for exactly the size it is
        // seen at, so that its text is sharp; the smaller ones stay with the atlas, which the graphics card scales down well.
        const same = (a: number, b: number) => a <= b * SAME && a >= b / SAME;
        let ownMade = 0;
        order.forEach((index, rank) => {
            const n = space.nodes[index];
            const seen = seenWith(n);
            // in pixels from the middle of the screen
            const x = (n.x - space.middleX) * seen;
            const y = (n.y - space.middleY) * seen;
            const inView = Math.abs(x) - n.width * seen / 2 < pixelWidth / 2 + seen * 4 && Math.abs(y) - n.height * seen / 2 < pixelHeight / 2 + seen * 4;
            let kept = this.kept.get(n.id);
            // a selected node is ringed with lines, a pixel wide however small it is seen (its outline in its picture would be
            // less); also when out of the view, so that its ring is not made again as the view moves
            if (ringed(n)) this.rings.ring(n.id, n.x, n.y, n.width / 2, n.height / 2, n.z, inkOf(n));
            if (gripped(n)) this.grips.grip(n.x, n.y, n.width / 2, n.height / 2, n.z, inkOf(n));
            if (!inView) {
                // not seen: nothing is worked out for it, and the picture of its own that it has is kept for when it is seen again
                if (kept !== undefined) {
                    kept.frame = this.frame;
                    kept.mesh.visible = false;
                }
            } else {
                const key = keyOf(n);
                const finest = MAX_PICTURE_SIDE / Math.max(n.width, n.height, 1);
                const whole = Math.min(dpr, finest);
                // a very big node has no place on a page: it has a picture of its own always
                const onPage = Math.max(n.width, n.height) * dpr <= MAX_ON_PAGE;
                let slot = onPage ? this.slots.get(n.id) : undefined;
                if (onPage && (slot === undefined || slot.key !== key)) {
                    if (painting < ATLAS_BUDGET_MS) {
                        made++;
                        const before = performance.now();
                        slot = this.put(n, key, paint(n, dpr), slot);
                        painting += performance.now() - before;
                    } else behind = true;
                }
                // at exactly the atlas's size the atlas is as good as a picture of its own
                const exact = seen > whole * EXACT_FROM && seen <= finest && !(onPage && same(seen, dpr));
                const wantsOwn = !onPage || exact;
                const wanted = exact ? seen : whole;
                const fits = kept !== undefined && kept.key === key && same(kept.pixelsPerUnit, wanted);
                // one that has no other picture gets its own at once; the others when the view is still, a few for each frame
                const mayMake = onPage ? !moving && ownMade < PICTURES_PER_FRAME : kept === undefined || (ownMade < PICTURES_PER_FRAME && (!moving || kept.key !== key));
                if (wantsOwn && !fits && mayMake) {
                    ownMade++;
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
                        const mesh = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: texture, ...PICTURE }));
                        kept ={ mesh, key, frame: this.frame, ...sizes };
                        this.kept.set(n.id, kept);
                        this.scene.add(mesh);
                    } else {
                        kept.mesh.material.map?.dispose();
                        kept.mesh.material.map = texture;
                        Object.assign(kept, { key, ...sizes });
                    }
                    // as big as the picture is, which is a little more than the node: whole pixels
                    kept.mesh.scale.set(kept.pixelWidth / wanted, kept.pixelHeight / wanted, 1);
                } else if (wantsOwn && !fits) pending = true;
                const own = wantsOwn && kept !== undefined && (!onPage || (kept.key === key && same(kept.pixelsPerUnit, wanted))) ? kept : undefined;
                /** How far to move a picture made with [pixelsPerUnit], its corner [left], [top] pixels from the screen's, to put that on a corner of a pixel: shown pixel for pixel, its text is blurred otherwise. */
                const snap = (pixelsPerUnit: number, middleX: number, middleY: number) => {
                    const left = pixelWidth / 2 + (n.x - space.middleX) * seen - middleX;
                    const top = pixelHeight / 2 + (n.y - space.middleY) * seen - middleY;
                    return same(pixelsPerUnit, seen) ? { x: (Math.round(left) - left) / seen, y: (Math.round(top) - top) / seen } : { x: 0, y: 0 };
                };
                if (own !== undefined) {
                    own.frame = this.frame;
                    own.mesh.visible = true;
                    const by = snap(own.pixelsPerUnit, own.middleX, own.middleY);
                    // the middle of the picture, in units from the middle of the node: across and down
                    const shiftX = (own.pixelWidth / 2 - own.middleX) / own.pixelsPerUnit + by.x;
                    const shiftY = (own.pixelHeight / 2 - own.middleY) / own.pixelsPerUnit + by.y;
                    own.mesh.position.set(n.x + shiftX, -(n.y + shiftY), -n.z);
                    own.mesh.renderOrder = rank;
                } else if (slot !== undefined) {
                    const by = snap(slot.pixelsPerUnit, slot.middleX, slot.middleY);
                    slot.page.quad(slot, n.x + by.x, n.y + by.y, n.z);
                } else {
                    // until its picture is made: an outline and a filling
                    const flat = flatOf(n, seen);
                    const hw = n.width / 2;
                    const hh = n.height / 2;
                    const inset = Math.min(flat.outline, hw, hh);
                    this.small.rectangle(n.x - hw, n.y - hh, n.x + hw, n.y + hh, n.z, ...rgb(flat.ink));
                    this.small.rectangle(n.x - hw + inset, n.y - hh + inset, n.x + hw - inset, n.y + hh - inset, n.z, ...rgb(flat.fill));
                }
            }
        });
        this.small.end();
        this.rings.end();
        this.grips.end();
        // e.g. after the colours changed: every picture is out of date at once
        if (behind && !this.filling) this.refill();
        this.pages.forEach((page) => {
            page.end();
            // the graphics card gets all of a page each time: not for every picture while they are being made
            if (page.dirty && (!pending || now - page.uploadedAt > PAGE_UPLOAD_MS)) {
                page.texture.needsUpdate = true;
                page.dirty = false;
                page.uploadedAt = now;
            }
            if (page.dirty) pending = true;
        });
        // what is no longer there, is behind the viewer, or is shown from the atlas now
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
        this.edges.mesh.visible = this.edgesShown;
        if (this.edgesShown) this.strips(space, width, height, ink, radius, fade);
        this.shells.draw(space.shells ?? [], background);
        const t2 = performance.now();
        this.renderer.render(this.scene, this.camera);
        const t3 = performance.now();
        this.spent = { nodes: t1 - now, edges: t2 - t1, render: t3 - t2, made, calls: this.renderer.info.render.calls };
        return pending;
    }

    // --- the atlas for somebody else to show: a headset ([vrServer]) ---

    /** One side of a page, in pixels. */
    readonly pageSide = PAGE;

    /** The pages of the atlas as pictures, each with a number that goes up when it changes. */
    get atlas(): { image: OffscreenCanvas; version: number }[] {
        return this.pages.map((page) => ({ image: page.canvas, version: page.version }));
    }

    /**
     * Where the picture of the node [id] is in the atlas: on which
     * page, the rectangle in pixels, how many pixels a unit of the
     * node is, and where in the rectangle the node's middle is.
     * Null while it has none, or for a node too big for a page.
     */
    placeOf(id: string): { page: number; x: number; y: number; width: number; height: number; pixelsPerUnit: number; middleX: number; middleY: number } | null {
        const slot = this.slots.get(id);
        return slot === undefined ? null : {
            page: this.pages.indexOf(slot.page), x: slot.x, y: slot.y, width: slot.width, height: slot.height,
            pixelsPerUnit: slot.pixelsPerUnit, middleX: slot.middleX, middleY: slot.middleY,
        };
    }

    /**
     * Have the pictures of [nodes] made in the background although
     * the graph is not drawn with depth here: somebody else shows it.
     * With [fit] every node gets one, a big node a coarser one.
     */
    prepare(nodes: N[], dpr: number, keyOf: (n: N) => string, paint: (n: N, pixelsPerUnit: number) => NodePicture, fit = false) {
        this.toFill = { nodes, dpr, keyOf, paint, fit };
        const stale = nodes.some((n) => SpaceRenderer.onPage(n, dpr, fit) !== null && this.slots.get(n.id)?.key !== keyOf(n));
        if (stale && !this.filling) this.refill();
    }

    /**
     * What is kept, for the nodes [ids]: how many of them have a
     * picture of their own and how many bytes those take on the
     * graphics card; how many have one in the atlas, how many pages
     * that has, how many bytes the pictures on them take, how many
     * the pages themselves here (they are whole, however full), and
     * how many on the graphics card, which has smaller copies too.
     */
    stats(ids: string[]): { own: number; ownBytes: number; atlas: number; pages: number; usedBytes: number; pageBytes: number; cardBytes: number } {
        const own = ids.map((id) => this.kept.get(id)).filter((it) => it !== undefined);
        const pageBytes = this.pages.length * PAGE * PAGE * 4;
        return {
            own: own.length,
            ownBytes: own.reduce((sum, it) => sum + it.pixelWidth * it.pixelHeight * 4, 0),
            atlas: ids.filter((id) => this.slots.has(id)).length,
            pages: this.pages.length,
            usedBytes: [...this.slots.values()].reduce((sum, it) => sum + it.width * it.height * 4, 0),
            pageBytes,
            cardBytes: Math.round(pageBytes * 4 / 3),
        };
    }

    /** Put [picture], which is of [n] and of what [key] tells, on a page of the atlas: where [old] is if it has the same size, else in a new place. */
    private put(n: N, key: string, picture: NodePicture, old: Slot | undefined): Slot {
        const width = picture.image.width;
        const height = picture.image.height;
        const reuse = old !== undefined && old.width === width && old.height === height;
        // the same place again, or the first page that has room
        let page: Page | undefined = reuse ? old.page : undefined;
        let at: { x: number; y: number } | null = reuse ? { x: old.x, y: old.y } : null;
        for (let i = 0; at === null && i < this.pages.length; i++) {
            page = this.pages[i];
            at = page.place(width, height);
        }
        if (at === null || page === undefined) {
            page = new Page(this.renderer.capabilities.getMaxAnisotropy());
            this.pages.push(page);
            this.scene.add(page.mesh);
            at = page.place(width, height)!;
        }
        page.ctx.clearRect(at.x, at.y, width, height);
        page.ctx.drawImage(picture.image, at.x, at.y);
        page.dirty = true;
        page.version++;
        const slot = {
            page, x: at.x, y: at.y, width, height, key, pixelsPerUnit: picture.pixelsPerUnit,
            middleX: (n.width / 2 + picture.margin) * picture.pixelsPerUnit,
            middleY: (n.height / 2 + picture.margin) * picture.pixelsPerUnit,
        };
        this.slots.set(n.id, slot);
        return slot;
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
