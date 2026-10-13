// The room a graph is shown in: where the graph is in it, and a made-up room for trying things without a headset.
// Shared by the window's page and the headset's.

import type { VrSurface } from "./vrServer";

/** A unit of the graph is this many metres: a node 150 units wide is 15 cm. */
export const METRES_PER_UNIT = 0.001;
/** Where in the room the middle of the graph is (metres; y up from the floor, -z ahead): in front of the eyes, at arm's length and a bit. */
export const GRAPH_AT: [number, number, number] = [0, 1.3, -1.2];
/** The colour the room is drawn with. */
export const ROOM_COLOUR = 0x44ccff;

/** A surface that stands, [width] along [across] (a level direction of length 1) and [height] up, its middle at [x], [y], [z]. */
function standing(label: string, x: number, y: number, z: number, across: [number, number], width: number, height: number): VrSurface {
    const [ax, az] = across;
    // its own x along the wall, its y out of it, its z down
    return { label, orientation: "vertical", matrix: [ax, 0, az, 0, -az, 0, ax, 0, 0, -1, 0, 0, x, y, z, 1], polygon: rectangle(width, height) };
}

/** A surface that lies, [width] across and [depth] deep, its middle at [x], [y], [z]. */
function lying(label: string, x: number, y: number, z: number, width: number, depth: number): VrSurface {
    return { label, orientation: "horizontal", matrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1], polygon: rectangle(width, depth) };
}

function rectangle(width: number, height: number): [number, number][] {
    return [[-width / 2, -height / 2], [width / 2, -height / 2], [width / 2, height / 2], [-width / 2, height / 2]];
}

/** A room for trying things without a headset: 4 m wide, 5 m deep and 2.5 m high, with a door, a window and a desk; the graph 1.8 m from the wall ahead. */
export function mockRoom(): VrSurface[] {
    return [
        lying("floor", 0, 0, -0.5, 4, 5),
        lying("ceiling", 0, 2.5, -0.5, 4, 5),
        standing("wall", 0, 1.25, -3, [1, 0], 4, 2.5),
        standing("wall", 0, 1.25, 2, [-1, 0], 4, 2.5),
        standing("wall", -2, 1.25, -0.5, [0, -1], 5, 2.5),
        standing("wall", 2, 1.25, -0.5, [0, 1], 5, 2.5),
        standing("door", -2, 1, 1, [0, -1], 0.9, 2),
        standing("window", 2, 1.5, -1, [0, 1], 1.5, 1.2),
        lying("desk", 0.9, 0.75, -2.5, 1.4, 0.7),
    ];
}

/**
 * The outlines of [surfaces] as lines in the graph: for each line its two ends, x, y and z of each after each other, in the
 * graph's units and its directions (y down, z away), the graph's point [middle] being where the graph is in the room ([GRAPH_AT]).
 */
export function outlines(surfaces: VrSurface[], middle: [number, number, number]): Float64Array {
    const inGraph = (s: VrSurface, [px, pz]: [number, number]) => {
        const m = s.matrix;
        const room = [m[0] * px + m[8] * pz + m[12], m[1] * px + m[9] * pz + m[13], m[2] * px + m[10] * pz + m[14]];
        return [
            middle[0] + (room[0] - GRAPH_AT[0]) / METRES_PER_UNIT,
            middle[1] - (room[1] - GRAPH_AT[1]) / METRES_PER_UNIT,
            middle[2] - (room[2] - GRAPH_AT[2]) / METRES_PER_UNIT,
        ];
    };
    return Float64Array.from(surfaces.flatMap((s) => s.polygon.flatMap((corner, i) => [...inGraph(s, corner), ...inGraph(s, s.polygon[(i + 1) % s.polygon.length])])));
}

/**
 * The walls among [surfaces] as seen from above: of each the two ends, x and z of each after each other (metres). A wall is a
 * surface that stands and is called one; doors and windows, which lie in walls, are not.
 */
export function wallsOf(surfaces: VrSurface[]): number[] {
    return surfaces.filter((s) => s.orientation === "vertical" && s.label === "wall" && s.polygon.length > 0).flatMap((s) => {
        // along the one of the surface's own two directions that is level (the other goes up the wall)
        const m = s.matrix;
        const level = Math.abs(m[1]) <= Math.abs(m[9]) ? 0 : 1;
        const along = s.polygon.map((p) => p[level]);
        const end = (a: number) => [m[level * 8] * a + m[12], m[level * 8 + 2] * a + m[14]];
        return [...end(Math.min(...along)), ...end(Math.max(...along))];
    });
}
