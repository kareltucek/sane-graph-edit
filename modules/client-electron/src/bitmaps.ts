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

/** A drawing of more pixels than this is kept at a lower scale. */
const MAX_BITMAP_PIXELS = 4e6;

export class Bitmaps {
    /** By what they are drawings of. All are kept, however many: there is no limit. */
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
        return Math.min(native, Math.sqrt(MAX_BITMAP_PIXELS / Math.max(1, width * height)));
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
        if (found !== undefined && !fits) this.drop(key);
        return fits ? found.bitmap : this.make(key, width, height, scale, draw);
    }

    private make(key: string, width: number, height: number, scale: number, draw: (ctx: OffscreenCanvasRenderingContext2D) => void): Bitmap {
        const pixelWidth = Math.max(1, Math.ceil(width * scale));
        const pixelHeight = Math.max(1, Math.ceil(height * scale));
        const canvas = new OffscreenCanvas(pixelWidth, pixelHeight);
        const ctx = canvas.getContext("2d")!;
        ctx.scale(scale, scale);
        draw(ctx);
        const bitmap = { image: canvas.transferToImageBitmap(), scale };
        this.kept.set(key, { bitmap, pixels: pixelWidth * pixelHeight });
        this.pixels += pixelWidth * pixelHeight;
        return bitmap;
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
