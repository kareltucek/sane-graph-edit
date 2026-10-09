import DOMPurify from "dompurify";
import { Graphviz } from "@hpcc-js/wasm-graphviz";
import { marked } from "marked";
import mermaid from "mermaid";

// Runs in a frame of its own, sandboxed: scripts, and nothing else —
// no Node, no files, not the page's origin. It is given the text of
// markdown nodes, which comes from documents and is not to be
// trusted, and gives back a picture of each: an SVG, which the
// page shows as an image, where nothing in it can run.

/** What the page asks: [markdown] as a picture, set in [font] at [fontPx], in the colours given, no wider than [maxWidth] before a line is broken. */
interface Request {
    id: number;
    markdown: string;
    font: string;
    fontPx: number;
    foreground: string;
    background: string;
    dark: boolean;
    maxWidth: number;
}

/** What it gets: the picture and its size in pixels, or why there is none. */
interface Answer {
    id: number;
    svg?: string;
    width?: number;
    height?: number;
    error?: string;
}

const stage = document.getElementById("stage") as HTMLDivElement;

/** How formatted text looks; in the picture itself, so that it looks the same wherever it is shown. Sizes are in em: they follow the node's font. */
const STYLE = `
.md { line-height: 1.2; white-space: normal; overflow-wrap: break-word; }
.md > :first-child { margin-top: 0; }
.md > :last-child { margin-bottom: 0; }
.md h1 { font-size: 1.5em; margin: 0.4em 0 0.3em; }
.md h2 { font-size: 1.3em; margin: 0.4em 0 0.3em; }
.md h3, .md h4, .md h5, .md h6 { font-size: 1.1em; margin: 0.4em 0 0.3em; }
.md p, .md ul, .md ol, .md pre, .md blockquote, .md table { margin: 0 0 0.6em; }
.md ul, .md ol { padding-left: 1.6em; }
.md code { font-family: monospace; opacity: 0.85; }
.md pre { white-space: pre; padding: 0.3em 0.5em; border: 1px solid currentColor; border-radius: 3px; }
.md blockquote { padding-left: 0.7em; border-left: 3px solid currentColor; opacity: 0.85; }
.md table { border-collapse: collapse; }
.md th, .md td { border: 1px solid currentColor; padding: 0.15em 0.5em; }
.md a { color: inherit; text-decoration: underline; }
.md hr { border: none; border-top: 1px solid currentColor; }
.md .diagram svg { max-width: none; }
.md .failed { white-space: pre; font-family: monospace; opacity: 0.7; }
`;

const graphviz = Graphviz.load();
let diagrams = 0;

/** The fenced blocks that are diagrams, by the language they are fenced as, and what draws each as an SVG. */
const DIAGRAMS: { [language: string]: (source: string, request: Request) => Promise<string> } = {
    mermaid: (source, request) => {
        // labels as SVG text: HTML inside a drawing does not survive being a picture inside a picture
        mermaid.initialize({
            startOnLoad: false,
            securityLevel: "strict",
            theme: request.dark ? "dark" : "default",
            fontFamily: request.font,
            htmlLabels: false,
            flowchart: { htmlLabels: false },
        });
        return mermaid.render(`diagram-${diagrams++}`, source).then((drawn) => drawn.svg);
    },
    dot: (source) => graphviz.then((it) => inTextColour(it.dot(source))),
    graphviz: (source) => graphviz.then((it) => inTextColour(it.dot(source))),
};

/** Graphviz draws black on white unless the source says otherwise: its black becomes the text's colour and its white page goes. */
function inTextColour(svg: string): string {
    return svg
        .replace(/<polygon fill="white" stroke="(?:transparent|none|white)"[^>]*\/>/, "")
        .replace(/(stroke|fill)="black"/g, '$1="currentColor"')
        // text has no fill of its own and would be black
        .replace(/<text /g, '<text fill="currentColor" ');
}

/** Replace every fenced block of [root] that is a diagram by its drawing; one that cannot be drawn stays as its source, marked. */
function drawDiagrams(root: HTMLElement, request: Request): Promise<void> {
    const blocks = Array.from(root.querySelectorAll("pre > code")).flatMap((code) => {
        const language = Array.from(code.classList).find((c) => c.startsWith("language-"))?.slice("language-".length) ?? "";
        const draw = DIAGRAMS[language];
        return draw === undefined ? [] : [{ pre: code.parentElement as HTMLElement, source: code.textContent ?? "", draw }];
    });
    return Promise.all(blocks.map(({ pre, source, draw }) =>
        draw(source, request).then((svg) => {
            const holder = document.createElement("div");
            holder.className = "diagram";
            // a drawing is markup made from the document's text: cleaned like the rest
            holder.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true, html: true }, ADD_TAGS: ["foreignObject"] });
            pre.replaceWith(holder);
        }).catch(() => {
            pre.classList.add("failed");
        }))).then(() => {});
}

function render(request: Request): Promise<Answer> {
    const root = document.createElement("div");
    root.className = "md";
    root.style.cssText =
        `display: inline-block; max-width: ${request.maxWidth}px; font: ${request.fontPx}px ${request.font}; color: ${request.foreground};`;
    root.innerHTML = DOMPurify.sanitize(marked.parse(request.markdown, { async: false }) as string);
    stage.replaceChildren(root);
    return drawDiagrams(root, request).then(() => {
        const box = root.getBoundingClientRect();
        // a pixel to spare: a border on the last pixel would be cut off
        const width = Math.ceil(box.width) + 1;
        const height = Math.ceil(box.height) + 1;
        // as XML: what an SVG holds must be well-formed, which the page's own markup need not be
        const body = new XMLSerializer().serializeToString(root);
        const svg =
            `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">` +
            `<foreignObject width="100%" height="100%"><div xmlns="http://www.w3.org/1999/xhtml"><style>${STYLE}</style>${body}</div></foreignObject></svg>`;
        stage.replaceChildren();
        return { id: request.id, svg, width, height };
    });
}

// one at a time: they share the stage
let last: Promise<unknown> = Promise.resolve();

window.addEventListener("message", (event) => {
    const request = event.data as Request;
    last = last
        .then(() => render(request))
        .catch((e) => ({ id: request.id, error: e instanceof Error ? e.message : String(e) }) as Answer)
        .then((answer) => window.parent.postMessage(answer, "*"));
});

const style = document.createElement("style");
style.textContent = STYLE;
document.head.appendChild(style);
window.parent.postMessage({ ready: true }, "*");
