// Entrypoint: `deno run --allow-read evals/run.ts`
// Prints a per-case pass/fail table plus a category summary, and exits with a non-zero code
// if any case failed (so this is CI-friendly).

import { runAll } from "./harness.ts";

const summary = await runAll();

for (const r of summary.results) {
  const mark = r.pass ? "PASS" : "FAIL";
  console.log(`[${mark}] ${r.category.padEnd(24)} ${r.id}`);
  if (!r.pass) {
    for (const f of r.failures) console.log(`       - ${f}`);
  }
}

console.log("\n--- Category summary ---");
for (const [category, stats] of Object.entries(summary.byCategory)) {
  console.log(`${category.padEnd(28)} ${stats.passed}/${stats.total}`);
}

console.log("\n--- Overall ---");
console.log(`${summary.passed}/${summary.total} passed (${((summary.passed / summary.total) * 100).toFixed(1)}%)`);

if (summary.failed > 0) {
  Deno.exit(1);
}
