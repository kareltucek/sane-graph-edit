// What nodes show, kept as bitmaps: a node's text is drawn once,
// at the scale it has when the view is not zoomed, and that drawing
// is what a view zoomed out shows of it. Closer than that a kept
// drawing would be coarse, and the text is drawn as it is. A view
// zoomed far out makes its drawings at half that scale, a quarter,
// and so on: a node shown small does not hold a big bitmap.

/** A kept drawing, [scale] bitmap pixels to one of the graph's units. */
export interface Bitmap {
    image: ImageBitmap;
    scale: number;
}

/**
 * At most [max] things by name; when there are more, the one used
 * longest ago is let go of.
 */
export class Recent<V> {
    private readonly kept = new Map<string, V>();

    constructor(private readonly max: number) {}

    has(key: string): boolean {
        return this.kept.has(key);
    }

    get(key: string): V | undefined {
        const found = this.kept.get(key);
        if (found !== undefined) {
            this.kept.delete(key);
            this.kept.set(key, found);
        }
        return found;
    }

    set(key: string, value: V) {
        this.kept.delete(key);
        this.kept.set(key, value);
        if (this.kept.size > this.max) this.kept.delete(this.kept.keys().next().value!);
    }
}

/** All kept drawings together hold at most this many pixels (four bytes each), unless the page knows better ([Bitmaps.limit]). */
const MAX_PIXELS = 64e6;

/** A drawing of more pixels than this is kept at a lower scale. */
const MAX_BITMAP_PIXELS = 4e6;

export class Bitmaps {
    /** By what they are drawings of; the one used longest ago first. */
    private readonly kept = new Map<string, { bitmap: Bitmap; pixels: number; frame: number }>();
    private pixels = 0;
    private frame = 0;

    private maxPixels = MAX_PIXELS;

    /** Keep at most [bytes] of drawings from now on. */
    set limit(bytes: number) {
        this.maxPixels = bytes / 4;
    }

    /** Whether a drawing is kept as [key]. */
    has(key: string): boolean {
        return this.kept.has(key);
    }

    /** How many drawings are kept, how many bytes they take, and how many they may take. */
    get stats(): { drawings: number; bytes: number; limit: number } {
        return { drawings: this.kept.size, bytes: this.pixels * 4, limit: this.maxPixels * 4 };
    }

    /** A new drawing of the view begins: what it uses is not let go of before the next one. */
    nextFrame() {
        this.frame++;
    }

    /** The scale a drawing of [width] by [height] units is kept at, when [native] is what it would be unless that is too big. */
    scaleOf(width: number, height: number, native: number): number {
        return Math.min(native, Math.sqrt(MAX_BITMAP_PIXELS / Math.max(1, width * height)));
    }

    /**
     * The drawing kept as [key], at [scale] or finer. One that is
     * not there is made: [draw] draws [width] by [height] units
     * from 0, 0, at [scale] pixels each. Null when there is no room
     * for it, all that is kept being in use in this drawing of the
     * view.
     */
    get(key: string, width: number, height: number, scale: number, draw: (ctx: OffscreenCanvasRenderingContext2D) => void): Bitmap | null {
        const found = this.kept.get(key);
        // one that is finer than asked for does as well
        if (found !== undefined && found.bitmap.scale >= scale) {
            // last used, last let go of
            this.kept.delete(key);
            this.kept.set(key, { ...found, frame: this.frame });
            return found.bitmap;
        }
        if (found !== undefined) this.drop(key);
        const pixelWidth = Math.max(1, Math.ceil(width * scale));
        const pixelHeight = Math.max(1, Math.ceil(height * scale));
        const pixels = pixelWidth * pixelHeight;
        if (!this.makeRoom(pixels)) return null;
        const canvas = new OffscreenCanvas(pixelWidth, pixelHeight);
        const ctx = canvas.getContext("2d")!;
        ctx.scale(scale, scale);
        draw(ctx);
        const bitmap = { image: canvas.transferToImageBitmap(), scale };
        this.kept.set(key, { bitmap, pixels, frame: this.frame });
        this.pixels += pixels;
        return bitmap;
    }

    /** Let go of the drawings used longest ago until [pixels] more fit; false if they cannot. */
    private makeRoom(pixels: number): boolean {
        let stuck = false;
        while (this.pixels + pixels > this.maxPixels && !stuck) {
            const oldest = this.kept.entries().next().value;
            stuck = oldest === undefined || oldest[1].frame === this.frame;
            if (oldest !== undefined && !stuck) this.drop(oldest[0]);
        }
        return this.pixels + pixels <= this.maxPixels;
    }

    private drop(key: string) {
        const entry = this.kept.get(key);
        if (entry !== undefined) {
            entry.bitmap.image.close();
            this.pixels -= entry.pixels;
            this.kept.delete(key);
        }
    }
}
