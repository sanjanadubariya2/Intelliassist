/**
 * Clears `.next` when it holds output from the *other* build mode.
 *
 * `next build` and `next dev` write incompatible output into the same
 * directory. Start a dev server after a production build and it tries to load
 * production chunks, which surfaces as
 *
 *   TypeError: Cannot read properties of undefined (reading 'call')
 *   TypeError: __webpack_modules__[moduleId] is not a function
 *
 * from `webpack.js` — an error that names webpack, looks fatal, and has
 * nothing to do with your code. It has cost this project two debugging
 * sessions, so it is now handled rather than remembered.
 *
 * `.next/BUILD_ID` is written only by `next build`, which makes it a reliable
 * marker of which mode produced the directory.
 *
 * `.next/cache` is preserved: it is the webpack and SWC cache, it is valid
 * across both modes, and discarding it turns a fast rebuild into a slow one
 * for no benefit.
 */

import { existsSync, readdirSync, rmSync } from "node:fs";
import { join } from "node:path";

const NEXT_DIR = ".next";
const BUILD_MARKER = join(NEXT_DIR, "BUILD_ID");

/** "dev" or "build" — whichever is about to run. */
const mode = process.argv[2] === "build" ? "build" : "dev";

if (!existsSync(NEXT_DIR)) {
  process.exit(0);
}

const hasProductionOutput = existsSync(BUILD_MARKER);

// A production build in place before `dev`, or dev output before `build`.
// The BUILD_ID marker distinguishes them; its absence means dev wrote this.
const stale = mode === "dev" ? hasProductionOutput : !hasProductionOutput;

if (!stale) {
  process.exit(0);
}

let removed = 0;
for (const entry of readdirSync(NEXT_DIR)) {
  // Keep the compiler caches; they are mode-independent and expensive to lose.
  if (entry === "cache") continue;
  rmSync(join(NEXT_DIR, entry), { recursive: true, force: true });
  removed += 1;
}

console.log(
  `[next] cleared ${removed} stale ${hasProductionOutput ? "production" : "development"} ` +
    `artefacts from .next before ${mode} (cache kept)`,
);
