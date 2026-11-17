import { cx } from "@/components/ui";

type Pose = "wave" | "munch" | "puzzled" | "cheer";

const BODY =
  "M60 40 h120 a40 40 0 0 1 40 40 v90 a40 40 0 0 1 -40 40 h-50 l-28 28 v-28 h-42 a40 40 0 0 1 -40 -40 v-90 a40 40 0 0 1 40 -40z";

/**
 * Inti, the landing page mascot: a speech bubble with a face.
 *
 * Purely decorative unless given a `label`. Its movement comes from the
 * `ia-*` classes in globals.css, which only animate inside `.ia-motion` and
 * stop for visitors who prefer reduced motion.
 */
export function Inti({
  pose = "wave",
  label,
  className,
}: {
  pose?: Pose;
  label?: string;
  className?: string;
}) {
  const a11y = label ? { role: "img", "aria-label": label } : { "aria-hidden": true };

  return (
    <svg viewBox="0 0 240 260" className={cx("overflow-visible", className)} {...a11y}>
      <ellipse className="ia-shadow" cx="120" cy="250" rx="72" ry="8" fill="#2a211c" opacity="0.14" />
      <g className="ia-bob">
        <line x1="120" y1="42" x2="120" y2="20" stroke="#1f4b3b" strokeWidth="6" strokeLinecap="round" />
        <circle className="ia-pulse" cx="120" cy="14" r="10" fill="#c0583a" />

        {(pose === "wave" || pose === "cheer") && (
          <g className="ia-wave">
            <path d="M196 120 q30 -22 32 -56" stroke="#1f4b3b" strokeWidth="15" strokeLinecap="round" fill="none" />
            <circle cx="228" cy="60" r="14" fill="#1f4b3b" />
          </g>
        )}
        {pose === "wave" && (
          <path d="M46 132 q-26 18 -20 48" stroke="#1f4b3b" strokeWidth="15" strokeLinecap="round" fill="none" />
        )}

        <path d={BODY} fill="#1f4b3b" stroke="#2a211c" strokeWidth="3" />
        <rect x="46" y="62" width="148" height="102" rx="36" fill="#fbf6f0" />

        {pose === "munch" ? (
          <>
            <path d="M82 108 q14 -12 28 0 M130 108 q14 -12 28 0" stroke="#2a211c" strokeWidth="6" strokeLinecap="round" fill="none" />
            <ellipse cx="120" cy="140" rx="14" ry="10" fill="#2a211c" />
          </>
        ) : (
          <g className="ia-blink">
            <ellipse cx="96" cy="106" rx="10" ry="13" fill="#2a211c" />
            <ellipse cx="144" cy="106" rx="10" ry="13" fill="#2a211c" />
            <circle cx="99" cy="101" r="3.5" fill="#fff" />
            <circle cx="147" cy="101" r="3.5" fill="#fff" />
          </g>
        )}

        {pose !== "munch" && (
          <>
            <circle cx="74" cy="134" r="9" fill="#f3a98c" opacity="0.75" />
            <circle cx="166" cy="134" r="9" fill="#f3a98c" opacity="0.75" />
          </>
        )}
        {pose === "wave" && <path d="M104 134 q16 16 32 0" stroke="#2a211c" strokeWidth="5" strokeLinecap="round" fill="none" />}
        {pose === "cheer" && <path d="M100 132 q20 22 40 0z" fill="#2a211c" />}
        {pose === "puzzled" && (
          <>
            <path d="M104 142 h32" stroke="#2a211c" strokeWidth="5" strokeLinecap="round" />
            <text x="204" y="40" fontFamily="var(--font-display)" fontSize="46" fontWeight="700" fill="#c0583a">
              ?
            </text>
          </>
        )}

        {pose === "wave" && (
          <g transform="rotate(-12 30 190)">
            <rect x="4" y="160" width="52" height="64" rx="7" fill="#fff" stroke="#2a211c" strokeWidth="2.5" />
            <rect x="12" y="172" width="34" height="6" rx="3" fill="#c0583a" />
            <rect x="12" y="185" width="36" height="4" rx="2" fill="#d9c6b3" />
            <rect x="12" y="195" width="30" height="4" rx="2" fill="#d9c6b3" />
            <rect x="12" y="205" width="34" height="4" rx="2" fill="#d9c6b3" />
          </g>
        )}
      </g>
    </svg>
  );
}

/** The small bubble-with-eyes mark used in the nav and the demo header. */
export function IntiMark({ className, inverted = false }: { className?: string; inverted?: boolean }) {
  return (
    <svg viewBox="0 0 40 40" className={className} aria-hidden>
      <path
        d="M10 6h20a8 8 0 0 1 8 8v10a8 8 0 0 1-8 8h-8l-6 6v-6h-6a8 8 0 0 1-8-8V14a8 8 0 0 1 8-8z"
        fill={inverted ? "#fbf6f0" : "#1f4b3b"}
      />
      {!inverted && <rect x="7" y="11" width="26" height="16" rx="7" fill="#fbf6f0" />}
      <circle cx="15" cy="19" r="2.6" fill="#2a211c" />
      <circle cx="25" cy="19" r="2.6" fill="#2a211c" />
    </svg>
  );
}
