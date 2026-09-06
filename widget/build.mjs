/**
 * Builds the widget into a single file a customer can drop onto their page.
 *
 * Two bundles, one output. `frame.ts` is compiled first and its code is
 * inlined into `loader.ts` as a string constant, so the iframe's document can
 * be written with `srcdoc` and there is no second request for a customer's
 * browser to make — or for their CSP to have to allow.
 */

import { build } from "esbuild";
import { mkdir, writeFile } from "node:fs/promises";

const watch = process.argv.includes("--watch");

const shared = {
  bundle: true,
  format: "iife",
  target: ["es2020", "chrome90", "firefox90", "safari15", "edge90"],
  minify: true,
  legalComments: "none",
};

async function compile() {
  // 1. The frame, kept in memory.
  const frame = await build({
    ...shared,
    entryPoints: ["src/frame.ts"],
    write: false,
    outfile: "frame.js",
  });

  const frameCode = frame.outputFiles[0].text;

  // `</script>` anywhere in the bundle would close the tag the srcdoc puts it
  // in. esbuild will not produce one from this source, but a future string
  // easily could, so it is neutralised rather than assumed away.
  const safeFrameCode = frameCode.replace(/<\/script/gi, "<\\/script");

  // 2. The loader, with the frame baked in.
  const loader = await build({
    ...shared,
    entryPoints: ["src/loader.ts"],
    write: false,
    outfile: "intelliassist.js",
    define: {
      __FRAME_SCRIPT__: JSON.stringify(safeFrameCode),
    },
  });

  const output = loader.outputFiles[0].text;

  await mkdir("dist", { recursive: true });
  await writeFile("dist/intelliassist.js", output, "utf8");

  const kb = (Buffer.byteLength(output, "utf8") / 1024).toFixed(1);
  console.log(`dist/intelliassist.js  ${kb} kB  (frame inlined)`);
}

await compile();

if (watch) {
  const { watch: watchDir } = await import("node:fs");
  console.log("watching src/…");
  watchDir("src", { recursive: true }, () => {
    compile().catch((error) => console.error(error.message));
  });
}
