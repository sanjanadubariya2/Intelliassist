/**
 * The hero scene: someone at a laptop by an arched window, plants, a mug, and
 * a cat asleep on the desk. Flat vector shapes in the brand palette, drawn
 * inline so it scales crisply and costs no request.
 */
export function HeroIllustration({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 420 380" className={className} role="img" aria-label="A person working on a laptop beside a sleeping cat">
      {/* arched window */}
      <path d="M150 300V120a80 80 0 0 1 160 0v180Z" fill="#f6e3d6" />
      <path d="M162 300V122a68 68 0 0 1 136 0v178Z" fill="#fdf3ea" />
      <path d="M230 54v246M162 170h136M162 236h136" stroke="#f0d6c6" strokeWidth="5" />

      {/* sun rays */}
      <g stroke="#e0896d" strokeWidth="4" strokeLinecap="round">
        <path d="M92 70l12 12M80 98h16M100 46l6 15" />
      </g>

      {/* plant, right */}
      <g>
        <path d="M330 300c-2-40 4-80 22-118" stroke="#5f7d5c" strokeWidth="3" fill="none" />
        <path d="M352 182c-24-6-36 10-34 30 20 2 34-10 34-30Z" fill="#7e9c7b" />
        <path d="M344 214c22-12 40-4 44 14-18 10-38 4-44-14Z" fill="#8fae8b" />
        <path d="M338 246c-22-8-36 4-36 22 20 4 34-6 36-22Z" fill="#6f8d6c" />
        <path d="M333 270c20-10 36-2 40 12-16 8-34 4-40-12Z" fill="#8fae8b" />
      </g>

      {/* plant, left (behind person) */}
      <g>
        <path d="M118 300c4-36 0-70-14-100" stroke="#5f7d5c" strokeWidth="3" fill="none" />
        <path d="M104 200c-26 2-34 22-26 40 22-4 32-20 26-40Z" fill="#7e9c7b" />
        <path d="M112 232c22-14 42-8 46 8-18 12-38 8-46-8Z" fill="#8fae8b" />
        <path d="M110 262c-20-6-34 6-32 22 20 2 32-8 32-22Z" fill="#6f8d6c" />
      </g>

      {/* person: hair back */}
      <path d="M196 120c-34 0-52 30-50 66 2 38-8 66-24 86 30 6 62-2 82-26 18 18 44 22 66 14-16-22-20-50-16-80 4-36-20-60-58-60Z" fill="#5b3a2c" />
      {/* bun */}
      <circle cx="214" cy="98" r="20" fill="#5b3a2c" />
      <path d="M200 112c6-6 22-6 28 0" stroke="#d97757" strokeWidth="4" strokeLinecap="round" fill="none" />
      {/* face */}
      <ellipse cx="214" cy="168" rx="36" ry="40" fill="#f4c9a8" />
      {/* fringe */}
      <path d="M178 160c4-26 22-40 44-40 18 0 30 12 32 30-18-10-40-6-58 4-6 4-12 6-18 6Z" fill="#5b3a2c" />
      {/* eyes and smile */}
      <path d="M198 170c3 3 8 3 11 0M220 170c3 3 8 3 11 0" stroke="#3b2a22" strokeWidth="2.2" strokeLinecap="round" fill="none" />
      <path d="M208 186c4 4 10 4 14 0" stroke="#3b2a22" strokeWidth="2" strokeLinecap="round" fill="none" />
      <circle cx="196" cy="182" r="6" fill="#ef9f86" opacity=".55" />
      <circle cx="234" cy="182" r="6" fill="#ef9f86" opacity=".55" />
      {/* sweater */}
      <path d="M150 300c0-48 26-86 64-86s64 38 64 86Z" fill="#c0583a" />
      <path d="M196 214c6 10 30 10 36 0" stroke="#a5462b" strokeWidth="4" fill="none" />
      {/* arm resting, hand at chin */}
      <path d="M248 232c14 6 20 24 6 40l-28 26" stroke="#b24e32" strokeWidth="18" strokeLinecap="round" fill="none" />
      <circle cx="244" cy="204" r="11" fill="#f4c9a8" />
      <path d="M246 214c6 8 6 20 2 30" stroke="#b24e32" strokeWidth="14" strokeLinecap="round" fill="none" />

      {/* desk */}
      <rect x="40" y="296" width="350" height="12" rx="6" fill="#e9c9b2" />
      <ellipse cx="215" cy="330" rx="170" ry="10" fill="#f0dccd" opacity=".7" />

      {/* laptop */}
      <path d="M232 230l92-6 12 70-96 4Z" fill="#e8e1d9" />
      <path d="M236 236l84-5 10 60-88 3Z" fill="#f6f1ea" />
      <path d="M286 254c-14 0-20 12-20 22 12 0 20-8 20-22Z" fill="#7e9c7b" />
      <path d="M268 280c6-8 12-14 18-18" stroke="#fdf9f4" strokeWidth="1.5" fill="none" />
      <path d="M226 296h120l-8 6H232Z" fill="#d8cfc5" />

      {/* mug */}
      <path d="M70 262h40v28a10 10 0 0 1-10 10H80a10 10 0 0 1-10-10Z" fill="#c0583a" />
      <path d="M110 268h6a10 10 0 0 1 0 20h-6" stroke="#c0583a" strokeWidth="5" fill="none" />
      <path d="M90 274c-6 0-9 6-9 10 6 0 9-4 9-10Z" fill="#f3c9b8" />
      <path d="M84 254c-4-6 4-8 0-14M96 254c-4-6 4-8 0-14" stroke="#e0b9a6" strokeWidth="2.5" strokeLinecap="round" fill="none" />

      {/* sleeping cat */}
      <g>
        <path d="M150 296c-6-28 18-44 44-42 22 2 34 14 34 30v12Z" fill="#fdf9f4" />
        <path d="M168 262c8-6 22-8 30-2-6 8-20 10-30 2Z" fill="#e0896d" />
        <path d="M206 266c8 2 14 10 14 20-8-2-14-10-14-20Z" fill="#5b3a2c" opacity=".85" />
        <circle cx="150" cy="282" r="17" fill="#fdf9f4" />
        <path d="M137 272l-3-14 12 8M156 266l8-11 2 14" fill="#fdf9f4" stroke="#fdf9f4" strokeWidth="2" strokeLinejoin="round" />
        <path d="M139 270l-1-6 5 3M158 265l3-4 1 6" fill="#ef9f86" />
        <path d="M142 284c2 2 5 2 7 0M153 284c2 2 5 2 7 0" stroke="#3b2a22" strokeWidth="1.6" strokeLinecap="round" fill="none" />
        <path d="M226 290c14 0 22-6 22-14" stroke="#fdf9f4" strokeWidth="7" strokeLinecap="round" fill="none" />
        <path d="M132 296c-4-6-2-10 6-10" stroke="#ece4da" strokeWidth="1.5" fill="none" />
      </g>
    </svg>
  );
}

/** A trailing leaf sprig for page corners. */
export function LeafSprig({ className, flip }: { className?: string; flip?: boolean }) {
  return (
    <svg
      viewBox="0 0 120 160"
      className={className}
      aria-hidden
      style={flip ? { transform: "scaleX(-1)" } : undefined}
    >
      <path d="M100 160C80 110 60 70 20 20" stroke="#7e9c7b" strokeWidth="3" fill="none" />
      <path d="M30 34c-20-4-28 10-24 26 18 0 28-10 24-26Z" fill="#8fae8b" />
      <path d="M48 64c4-20 22-24 34-14-6 16-20 22-34 14Z" fill="#7e9c7b" />
      <path d="M58 92c-20 0-30 14-24 28 18-2 28-12 24-28Z" fill="#6f8d6c" />
      <path d="M76 118c6-18 24-20 34-8-8 14-22 18-34 8Z" fill="#8fae8b" />
    </svg>
  );
}
