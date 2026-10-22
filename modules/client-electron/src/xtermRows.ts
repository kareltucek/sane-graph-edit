import type { IBufferLine } from "@xterm/xterm";

// The rows of an xterm buffer as xterm keeps them, which its public API gives only cell by cell, a call for each thing of
// each cell. This is the one place that reaches into xterm (6.0) for them; where it finds them other than it expects, as
// another version of xterm may keep them, it gives none, and the cells are read through the public API.

/**
 * A row as xterm keeps it: three numbers a cell ([data]: what is in it, its foreground and its background, with their
 * flags), and in [extras] what does not fit in them: the characters of a cell made of several, and underline styles.
 * Two rows that are the same here look the same; two that differ may still look alike.
 */
export interface RawRow {
    data: Uint32Array;
    extras: string;
}

/** What xterm keeps of a row in its `BufferLine`, behind the public `IBufferLine`. */
interface Kept {
    _data?: unknown;
    _combined?: { [index: number]: string };
    _extendedAttrs?: { [index: number]: { underlineStyle?: number } | undefined };
}

const CELL_SIZE = 3;

/** The row [line] as xterm keeps it, its numbers xterm's own (not to be changed, and changed by xterm later); null when xterm keeps it otherwise. */
export function rawRow(line: IBufferLine): RawRow | null {
    const kept = (line as unknown as { _line?: Kept })._line;
    const data = kept?._data;
    const ours = data instanceof Uint32Array && data.length >= line.length * CELL_SIZE && typeof kept?._combined === "object" && typeof kept?._extendedAttrs === "object";
    const combined = ours ? Object.entries(kept!._combined!).map(([at, text]) => `${at}:${text}`).join("\u0001") : "";
    const extended = ours ? Object.entries(kept!._extendedAttrs!).map(([at, attrs]) => `${at}:${attrs?.underlineStyle}`).join("\u0001") : "";
    return ours ? { data: (data as Uint32Array).subarray(0, line.length * CELL_SIZE), extras: `${combined}\u0002${extended}` } : null;
}

// Loops, not reduce or every: these go through every number of every row shown, and a call for each costs several times as much.

/** A number for [row] with [also], the same for rows that are the same. */
export function hashOf(row: RawRow, also: number[]): number {
    let hash = 2166136261;
    for (let i = 0; i < row.data.length; i++) hash = Math.imul(hash ^ row.data[i], 16777619);
    for (let i = 0; i < row.extras.length; i++) hash = Math.imul(hash ^ row.extras.charCodeAt(i), 16777619);
    for (let i = 0; i < also.length; i++) hash = Math.imul(hash ^ also[i], 16777619);
    return hash >>> 0;
}

/** Whether rows [a] and [b] are the same. */
export function same(a: RawRow, b: RawRow): boolean {
    let equal = a.data.length === b.data.length && a.extras === b.extras;
    for (let i = 0; equal && i < a.data.length; i++) equal = a.data[i] === b.data[i];
    return equal;
}

/** [row] kept as it is now: xterm changes its numbers in place. */
export function copied(row: RawRow): RawRow {
    return { data: row.data.slice(), extras: row.extras };
}
