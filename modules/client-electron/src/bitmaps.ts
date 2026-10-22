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

/** A drawing longer or higher than this many pixels is kept at a lower scale: a canvas, and a texture of the graphics card, is no bigger. */
const MAX_BITMAP_SIDE = 16384;

/** The drawings kept take no more than this (1 GB); over it, those used longest ago are let go of. */
const MAX_KEPT_PIXELS = 2 ** 30 / 4;

export class Bitmaps {
    /** By what they are drawings of, the one used longest ago first. */
    private readonly kept = new Map<string, { bitmap: Bitmap; pixels: number }>();
    private pixels = 0;

    /** Whether a drawing is kept as [key]. */
    has(key: string): boolean {
        return this.kept.has(key);
    }

    /** How many drawings are kept and how many bytes they take. */
    get stats(): { drawings: number; bytes: number } {
        return { drawings: this.kept.size, bytes: this.pixels * 4 };
    }

    /** The scale a drawing of [width] by [height] units is kept at, when [native] is what it would be unless that is too big. */
    scaleOf(width: number, height: number, native: number): number {
        return Math.min(native, MAX_BITMAP_SIDE / Math.max(1, width, height));
    }

    /**
     * The drawing kept as [key], at [scale] or finer. One that is
     * not there is made: [draw] draws [width] by [height] units
     * from 0, 0, at [scale] pixels each.
     */
    get(key: string, width: number, height: number, scale: number, draw: (ctx: OffscreenCanvasRenderingContext2D) => void): Bitmap {
        const found = this.kept.get(key);
        // one that is finer than asked for does as well; a coarser one is replaced
        const fits = found !== undefined && found.bitmap.scale >= scale;
        if (found !== undefined) {
            this.kept.delete(key);
            if (fits) this.kept.set(key, found);
            else this.release(found);
        }
        return fits ? found.bitmap : this.make(key, width, height, scale, draw);
    }

    private make(key: string, width: number, height: number, scale: number, draw: (ctx: OffscreenCanvasRenderingContext2D) => void): Bitmap {
        const pixelWidth = Math.min(MAX_BITMAP_SIDE, Math.max(1, Math.ceil(width * scale)));
        const pixelHeight = Math.min(MAX_BITMAP_SIDE, Math.max(1, Math.ceil(height * scale)));
        const canvas = new OffscreenCanvas(pixelWidth, pixelHeight);
        const ctx = canvas.getContext("2d")!;
        ctx.scale(scale, scale);
        draw(ctx);
        const bitmap = { image: canvas.transferToImageBitmap(), scale };
        this.kept.set(key, { bitmap, pixels: pixelWidth * pixelHeight });
        this.pixels += pixelWidth * pixelHeight;
        // those used longest ago first, from the start and only as far as needed (a copy of all the keys for each drawing made
        // took long with thousands kept); the one just made stays, whatever it takes
        const oldest = this.kept.keys();
        let old = oldest.next();
        while (this.pixels > MAX_KEPT_PIXELS && !old.done && old.value !== key) {
            this.release(this.kept.get(old.value)!);
            this.kept.delete(old.value);
            old = oldest.next();
        }
        return bitmap;
    }

    private release(entry: { bitmap: Bitmap; pixels: number }) {
        entry.bitmap.image.close();
        this.pixels -= entry.pixels;
    }
}
