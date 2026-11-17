/**
 * The palette for places CSS tokens cannot reach.
 *
 * Recharts writes colours into SVG attributes, where `var(--color-…)` is not
 * reliably resolved, so charts take literal values. They are kept here, next
 * to nothing else, and must match the tokens in globals.css.
 */
export const chart = {
  primary: "#1f4b3b",
  primarySoft: "#cfe0d5",
  clay: "#c0583a",
  claySoft: "#f3c9b8",
  sage: "#7e9c7b",
  amber: "#d39b2a",
  grid: "#eee2d5",
  axis: "#a8998e",
};

export const chartAxis = { fontSize: 11, fill: chart.axis };

export const chartTooltip = {
  borderRadius: 12,
  border: `1px solid ${chart.grid}`,
  fontSize: 12,
  boxShadow: "0 8px 24px -12px rgb(42 33 28 / 0.18)",
};
