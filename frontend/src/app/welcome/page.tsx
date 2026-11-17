"use client";

import { useState, type CSSProperties, type ReactNode } from "react";
import Link from "next/link";
import { ArrowDown, ArrowRight, BookOpen } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { Inti, IntiMark } from "@/components/inti";
import { cx } from "@/components/ui";

/**
 * The product page, for visitors who are not signed in.
 *
 * It tells the product as a short story, one chapter per thing IntelliAssist
 * does: learn from documents, answer with sources, hand off to a person, and
 * point out gaps. Every claim is something the product does today.
 *
 * All motion lives in globals.css under `.ia-motion` and switches off for
 * visitors who prefer reduced motion. Scroll reveals need scroll-driven
 * animations; browsers without them show the page unanimated.
 */

const MARQUEE = [
  "Reads your documents",
  "Cites every answer",
  "Never invents",
  "Hands off to people",
  "Finds the gaps",
  "Matches your brand",
];

const FORMATS = ["PDF", "DOCX", "TXT", "Markdown"];

const MOST_ASKED = [
  { label: "Delivery times", count: 182, pct: 100, color: "bg-accent" },
  { label: "Order tracking", count: 141, pct: 77, color: "bg-accent" },
  { label: "Returns", count: 118, pct: 65, color: "bg-sage" },
  { label: "Cash on delivery (unanswered)", count: 17, pct: 12, color: "bg-clay" },
];

const DEMO = [
  {
    q: "Do you deliver across India?",
    a: "Yes, to over 19,000 pin codes. Metro cities get orders in 2 to 4 working days, everywhere else in 4 to 7.",
    source: "Shipping policy · p.1",
    grounded: true,
  },
  {
    q: "Can I return a rug?",
    a: "Rugs can be returned within 15 days of delivery if they’re unused and rolled, not folded. Pickup is free.",
    source: "Returns and refunds · §2",
    grounded: true,
  },
  {
    q: "Is cash on delivery okay for ₹25,000?",
    a: "I couldn’t find that in the store’s documents, so I won’t guess. I’ve flagged it for the team so a person can follow up.",
    source: "No matching passage",
    grounded: false,
  },
];

/** CSS custom properties for the float and fly keyframes. */
function vars(v: Record<string, string>): CSSProperties {
  return v as CSSProperties;
}

const hardShadow = "shadow-[6px_6px_0_var(--color-ink)]";

export default function WelcomePage() {
  const { user } = useAuth();
  const [pick, setPick] = useState(0);
  const demo = DEMO[pick];
  const startHref = user ? "/" : "/register";

  return (
    <div className="ia-motion relative min-h-dvh overflow-x-hidden bg-canvas text-ink">
      <div className="ia-progress fixed inset-x-0 top-0 z-[60] h-[5px] bg-clay" aria-hidden />

      {/* ---------------------------------------------------------------- nav */}
      <header className="sticky top-0 z-50 border-b-2 border-ink bg-canvas/85 backdrop-blur">
        <div className="mx-auto flex min-h-[76px] max-w-[1200px] flex-wrap items-center gap-6 px-6">
          <Link href="/welcome" aria-label="IntelliAssist home" className="flex items-center gap-2.5">
            <IntiMark className="h-9 w-9" />
            <span className="font-display text-[21px] font-semibold tracking-tight">IntelliAssist</span>
          </Link>
          <nav aria-label="Story" className="hidden gap-6 text-[15px] font-medium text-ink/80 md:flex">
            <a href="#learn" className="hover:text-ink">How Inti learns</a>
            <a href="#answer" className="hover:text-ink">Answers</a>
            <a href="#handoff" className="hover:text-ink">Handoff</a>
            <a href="#try" className="hover:text-ink">Try it</a>
          </nav>
          <div className="ml-auto flex items-center gap-3">
            {user ? (
              <PillLink href="/" variant="primary" small>
                Open dashboard <ArrowRight className="h-4 w-4" />
              </PillLink>
            ) : (
              <>
                <Link href="/login" className="inline-flex min-h-11 items-center px-2 text-[15px] font-semibold">
                  Sign in
                </Link>
                <PillLink href="/register" variant="primary" small>
                  Start free
                </PillLink>
              </>
            )}
          </div>
        </div>
      </header>

      {/* --------------------------------------------------------------- hero */}
      <section className="relative overflow-hidden pb-28 pt-16">
        <div className="ia-morph absolute -right-36 -top-20 h-[620px] w-[620px] bg-accent-soft" aria-hidden />
        <div className="ia-morph absolute -bottom-16 -left-32 h-[340px] w-[340px] bg-clay-soft [animation-delay:-5s]" aria-hidden />
        {/* These two would sit on the headline at phone width. */}
        <Shape className="left-[6%] top-[18%] hidden h-[22px] w-[22px] rounded-full bg-clay md:block" style={vars({ "--dx": "10px", "--dy": "-22px" })} />
        <Shape className="left-[44%] top-[10%] hidden h-[30px] w-[30px] md:block bg-sage [clip-path:polygon(50%_0,100%_100%,0_100%)] [animation-delay:-2s]" style={vars({ "--r": "20deg" })} />
        <Shape className="bottom-[12%] left-[38%] h-[46px] w-[46px] rounded-full border-[6px] border-ink [animation-delay:-4s]" style={vars({ "--dx": "-14px" })} />

        <div className="relative mx-auto flex max-w-[1200px] flex-wrap items-center gap-10 px-6">
          <div className="min-w-0 flex-[1_1_460px]">
            <Kicker>Say hello to Inti</Kicker>
            <h1 className="font-display text-[clamp(40px,6vw,80px)] font-semibold leading-none tracking-[-0.02em]">
              The assistant that{" "}
              {/* The comma rides with the underlined words so it can never start a line. */}
              <span className="whitespace-nowrap">
                <span className="relative inline-block text-accent">
                  reads first
                  <svg viewBox="0 0 200 14" preserveAspectRatio="none" aria-hidden className="absolute -bottom-3 left-0 h-4 w-full">
                    <path d="M2 9 C 50 2, 110 2, 198 8" fill="none" stroke="var(--color-clay)" strokeWidth="5" strokeLinecap="round" />
                  </svg>
                </span>
                ,
              </span>{" "}
              then answers.
            </h1>
            <p className="mt-8 max-w-[520px] text-[20px] leading-relaxed text-muted">
              IntelliAssist puts a friendly chat helper on your website. It learns from your own documents, shows
              where every answer came from, and calls your team when it doesn’t know.
            </p>
            <div className="mt-9 flex flex-wrap gap-4">
              <PillLink href={startHref} variant="primary">
                {user ? "Open dashboard" : "Build your Inti, free"}
              </PillLink>
              <PillLink href="#learn" variant="secondary">
                Follow the story <ArrowDown className="h-[18px] w-[18px]" strokeWidth={2.2} />
              </PillLink>
            </div>
          </div>

          <div className="relative grid min-h-[520px] min-w-0 flex-[1_1_440px] place-items-center">
            <svg viewBox="0 0 400 400" aria-hidden className="ia-spin absolute h-auto w-[470px] max-w-full">
              <circle cx="200" cy="200" r="180" fill="none" stroke="var(--color-ink)" strokeWidth="2" strokeDasharray="4 14" />
              <circle cx="380" cy="200" r="10" fill="var(--color-clay)" />
              <circle cx="20" cy="200" r="7" fill="var(--color-accent)" />
              <rect x="190" y="12" width="18" height="18" rx="4" fill="var(--color-sage)" />
            </svg>
            <Inti
              pose="wave"
              label="Inti, the IntelliAssist mascot: a green speech bubble with a friendly face, waving and holding a document"
              className="relative h-auto w-[300px] max-w-[80%]"
            />
            <Floating className="left-0 top-[6%] [animation-delay:-1s]">
              <Bubble side="left">
                Delivery in 2–4 days <Chip className="ml-1 bg-[#f3ece4] text-[#5f5249]">p.1</Chip>
              </Bubble>
            </Floating>
            <Floating className="bottom-[14%] right-0 [animation-delay:-3s]" style={vars({ "--dx": "-6px", "--dy": "-14px" })}>
              <Bubble side="right" className="bg-clay-soft">Returns within 15 days</Bubble>
            </Floating>
            <Floating className="right-[4%] top-[16%] [animation-delay:-2s]">
              <span className="flex items-center gap-1.5 rounded-full border-2 border-ink bg-surface px-3.5 py-2.5" aria-hidden>
                {[0, 0.15, 0.3].map((d) => (
                  <span key={d} className="ia-typing h-2 w-2 rounded-full bg-muted" style={{ animationDelay: `${d}s` }} />
                ))}
              </span>
            </Floating>
            <Sparkle className="bottom-[10%] left-[12%] h-[30px] w-[30px] text-clay" />
            <Sparkle className="right-[18%] top-[2%] h-5 w-5 text-accent [animation-delay:-1.2s]" />
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------ marquee */}
      <div className="-mx-5 -rotate-[1.5deg] overflow-hidden bg-ink py-[18px] text-canvas" aria-hidden>
        <div className="ia-marquee flex w-max">
          {[...MARQUEE, ...MARQUEE].map((word, i) => (
            <span key={i} className="inline-flex items-center gap-7 whitespace-nowrap px-7 font-display text-[26px] font-semibold">
              {word}
              <StarPath className="h-[18px] w-[18px] text-clay" />
            </span>
          ))}
        </div>
      </div>

      {/* -------------------------------------------------- chapter 1: learn */}
      <section id="learn" className="mx-auto flex max-w-[1200px] scroll-mt-24 flex-wrap items-center gap-14 px-6 pb-28 pt-36">
        <div className="ia-reveal min-w-0 flex-[1_1_420px]">
          <Kicker>Chapter 1 · Learn</Kicker>
          <ChapterTitle>Feed Inti your documents. It reads every page.</ChapterTitle>
          <p className="mt-5 max-w-[480px] text-[18px] leading-relaxed text-muted">
            Upload your policies, product guides and FAQs. IntelliAssist splits them into passages it can find in a
            blink, and those passages become the only place it takes answers from.
          </p>
          <div className="mt-6 flex flex-wrap gap-2.5">
            {FORMATS.map((f) => (
              <span key={f} className="rounded-full border-2 border-ink bg-surface px-3.5 py-1.5 text-[13px] font-semibold">
                {f}
              </span>
            ))}
          </div>
        </div>
        <div
          className={cx(
            "ia-reveal-2 relative h-[400px] min-w-0 flex-[1_1_440px] overflow-hidden rounded-[32px] border-2 border-ink bg-accent-soft",
            "shadow-[8px_8px_0_var(--color-ink)]",
          )}
          aria-hidden
        >
          <FlyingDoc className="left-[8%] top-[12%]" tx="250px" ty="120px" bar="bg-clay" label="PDF" />
          <FlyingDoc className="left-[4%] top-[60%] [animation-delay:-1.5s]" tx="270px" ty="-70px" bar="bg-accent" label="TXT" tint />
          <FlyingDoc className="left-[26%] top-[2%] [animation-delay:-3s]" tx="160px" ty="160px" bar="bg-sage" label="DOCX" />
          <Inti pose="munch" className="absolute bottom-[8%] right-[8%] h-auto w-[170px]" />
          <span className="absolute bottom-[18px] left-5 rounded-full border-2 border-ink bg-surface px-3 py-1.5 text-[13px] font-semibold">
            nom nom · passages indexed
          </span>
        </div>
      </section>

      {/* ------------------------------------------------- chapter 2: answer */}
      <section id="answer" className="relative scroll-mt-20 overflow-hidden bg-accent py-32 text-canvas">
        <div className="ia-spin absolute -right-28 -top-28 h-[360px] w-[360px] rounded-full border-[3px] border-dashed border-canvas/25" aria-hidden />
        <Shape className="bottom-[10%] left-[4%] h-[60px] w-[60px] bg-clay [clip-path:polygon(50%_0,100%_50%,50%_100%,0_50%)]" />
        <div className="relative mx-auto flex max-w-[1200px] flex-wrap-reverse items-center gap-14 px-6">
          <div className="flex min-w-0 max-w-[520px] flex-[1_1_440px] flex-col gap-4">
            <p className="ia-reveal self-end rounded-[22px] rounded-br-md border-2 border-ink bg-clay px-[18px] py-3.5 text-[17px] text-white">
              Is the teak bench okay outdoors?
            </p>
            <div className="ia-reveal-2 self-start rounded-[22px] rounded-bl-md border-2 border-ink bg-canvas px-[18px] py-4 text-[17px] leading-relaxed text-ink shadow-[6px_6px_0_var(--color-ink)]">
              On a covered balcony, yes. It’s treated teak, so keep it out of direct rain and oil it every six months.
              <div className="mt-3 flex flex-wrap gap-1.5">
                <Chip className="bg-[#f3ece4] text-[#5f5249]">Care instructions · p.3</Chip>
                <Chip className="bg-ok-soft text-[#256640]">Grounded</Chip>
              </div>
            </div>
            <p className="ia-reveal-3 flex items-center gap-2.5 self-start text-[14px] text-[#f3c3ae]">
              <BookOpen className="h-[18px] w-[18px]" /> Every reply names the document and page it came from.
            </p>
          </div>
          <div className="ia-reveal min-w-0 flex-[1_1_400px]">
            <Kicker light>Chapter 2 · Answer</Kicker>
            <ChapterTitle className="text-white">Every answer comes with its receipts.</ChapterTitle>
            <p className="mt-5 max-w-[460px] text-[18px] leading-relaxed text-[#dfe7df]">
              Inti quotes your own words back and says where they live. Customers can trust it, and so can you.
              Nothing is made up to fill a silence.
            </p>
          </div>
        </div>
      </section>

      {/* ------------------------------------------------ chapter 3: handoff */}
      <section id="handoff" className="mx-auto flex max-w-[1200px] scroll-mt-24 flex-wrap items-center gap-14 px-6 pb-28 pt-36">
        <div className="ia-reveal min-w-0 flex-[1_1_400px]">
          <Kicker>Chapter 3 · Hand off</Kicker>
          <ChapterTitle>Stuck? Inti calls a teammate.</ChapterTitle>
          <p className="mt-5 max-w-[460px] text-[18px] leading-relaxed text-muted">
            When the documents don’t have the answer, or a customer asks for a person, the conversation lands in your
            Handoff Inbox with the full transcript. Assign it, leave notes for each other, and track it to resolution.
          </p>
        </div>
        <div
          className={cx(
            "ia-reveal-2 flex min-w-0 flex-[1_1_460px] flex-wrap items-center justify-center rounded-[32px] border-2 border-ink bg-clay-soft px-5 py-10",
            "shadow-[8px_8px_0_var(--color-ink)]",
          )}
          aria-hidden
        >
          <figure className="m-0 flex flex-col items-center gap-2.5">
            <Inti pose="puzzled" className="h-[125px] w-[130px]" />
            <figcaption className="text-[14px] font-semibold">Inti</figcaption>
          </figure>
          <svg viewBox="0 0 160 60" className="h-[60px] w-[160px]">
            <path className="ia-dash" d="M6 30 C 50 0, 110 60, 150 30" fill="none" stroke="var(--color-ink)" strokeWidth="3" strokeLinecap="round" />
            <path d="M140 20 l12 10 -12 10" fill="none" stroke="var(--color-ink)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <figure className="m-0 flex flex-col items-center gap-2.5">
            <Teammate />
            <figcaption className="text-[14px] font-semibold">Your teammate</figcaption>
          </figure>
          <div className="mt-6 flex basis-full justify-center">
            <div className="flex items-center gap-3 rounded-3xl border-2 border-ink bg-surface px-[18px] py-3.5 shadow-[4px_4px_0_var(--color-ink)]">
              <svg viewBox="0 0 10 10" className="h-2.5 w-2.5 overflow-visible">
                <circle className="ia-pulse" cx="5" cy="5" r="5" fill="var(--color-clay)" />
              </svg>
              <span className="text-[14px]">
                <strong className="font-semibold">New in Handoff Inbox:</strong> “Is COD okay for ₹25,000?”
              </span>
            </div>
          </div>
        </div>
      </section>

      {/* ------------------------------------------------ chapter 4: improve */}
      <section className="border-y-2 border-ink bg-surface py-32">
        <div className="mx-auto flex max-w-[1200px] flex-col gap-12 px-6">
          <div className="ia-reveal max-w-[720px]">
            <Kicker>Chapter 4 · Improve</Kicker>
            <ChapterTitle>Inti gets sharper every week, and tells you how.</ChapterTitle>
            <p className="mt-5 text-[18px] leading-relaxed text-muted">
              IntelliAssist spots the questions nobody has answered, the files that went stale and the ones that
              contradict each other, so a ten-minute fix closes a gap for good.
            </p>
          </div>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(300px,1fr))] gap-6">
            <article className={cx("ia-reveal flex flex-col gap-[18px] rounded-3xl border-2 border-ink bg-surface p-7", hardShadow)}>
              <h3 className="text-[18px] font-semibold">Most asked this week</h3>
              {MOST_ASKED.map((row) => (
                <div key={row.label}>
                  <div className="mb-1.5 flex text-[14px]">
                    <span className="flex-1">{row.label}</span>
                    <span className="text-muted">{row.count}</span>
                  </div>
                  <div className="h-3.5 overflow-hidden rounded-full border-2 border-ink bg-[#f3ece4]">
                    <div className={cx("ia-grow h-full", row.color)} style={{ width: `${row.pct}%` }} />
                  </div>
                </div>
              ))}
            </article>
            <article className={cx("ia-reveal-2 flex flex-wrap items-center gap-[22px] rounded-3xl border-2 border-ink bg-surface p-7", hardShadow)}>
              <svg viewBox="0 0 120 120" className="h-[130px] w-[130px]" role="img" aria-label="Knowledge health score 78 out of 100">
                <circle cx="60" cy="60" r="50" fill="none" stroke="#f3ece4" strokeWidth="14" />
                <circle
                  className="ia-ring"
                  cx="60"
                  cy="60"
                  r="50"
                  fill="none"
                  stroke="var(--color-accent)"
                  strokeWidth="14"
                  strokeLinecap="round"
                  strokeDasharray="314"
                  strokeDashoffset="69"
                  transform="rotate(-90 60 60)"
                />
                <text x="60" y="70" textAnchor="middle" fontFamily="var(--font-display)" fontSize="32" fontWeight="700" fill="var(--color-ink)">
                  78
                </text>
              </svg>
              <div className="min-w-[160px] flex-1">
                <h3 className="mb-1.5 text-[18px] font-semibold">Knowledge health</h3>
                <p className="text-[15px] leading-normal text-muted">2 conflicts, 5 stale files. Fix them and the score climbs.</p>
              </div>
            </article>
            <article className={cx("ia-reveal-3 flex flex-col gap-3 rounded-3xl border-2 border-ink bg-clay-soft p-7", hardShadow)}>
              <h3 className="text-[18px] font-semibold">Gap found</h3>
              <p className="text-[16px] leading-normal">
                17 people asked about <strong>cash on delivery limits</strong>. Nothing in your documents covers it.
              </p>
              <span className="mt-1.5 inline-flex min-h-[42px] items-center self-start rounded-full bg-accent px-[18px] text-[14px] font-semibold text-white shadow-[0_6px_0_var(--color-accent-strong)]">
                Write the answer
              </span>
            </article>
          </div>
        </div>
      </section>

      {/* ------------------------------------------------------------- try it */}
      <section id="try" className="mx-auto flex max-w-[1200px] scroll-mt-24 flex-wrap items-center gap-12 px-6 py-32">
        <div className="ia-reveal min-w-0 flex-[1_1_380px]">
          <Kicker>Your turn</Kicker>
          <ChapterTitle>Ask Inti something.</ChapterTitle>
          <p className="mt-5 max-w-[440px] text-[18px] leading-relaxed text-muted">
            This one knows a sample home goods store. Pick a question and watch what it does, including when it
            doesn’t know.
          </p>
          <div className="mt-7 flex flex-col items-start gap-2.5" role="group" aria-label="Sample questions">
            {DEMO.map((item, i) => (
              <button
                key={item.q}
                type="button"
                aria-pressed={i === pick}
                onClick={() => setPick(i)}
                className={cx(
                  "min-h-12 rounded-full border-2 border-ink px-5 text-left text-[15px] font-semibold shadow-[4px_4px_0_var(--color-ink)] transition-transform hover:-translate-y-0.5",
                  i === pick ? "bg-accent text-white" : "bg-surface text-ink",
                )}
              >
                {item.q}
              </button>
            ))}
          </div>
        </div>
        <div className={cx("ia-reveal-2 min-w-0 flex-[1_1_440px] overflow-hidden rounded-[28px] border-2 border-ink bg-surface", hardShadow)}>
          <div className="flex items-center gap-3 border-b-2 border-ink bg-accent px-5 py-4 text-white">
            <IntiMark inverted className="h-9 w-9" />
            <div>
              <p className="font-semibold">Inti · Sample store</p>
              <p className="text-[12px] opacity-85">Demo, answers from sample documents</p>
            </div>
          </div>
          <div className="flex min-h-[300px] flex-col gap-3.5 bg-[#fffdfa] p-6" aria-live="polite">
            <p className="max-w-[80%] self-end rounded-[20px] rounded-br-md bg-clay px-4 py-3 text-[16px] text-white">{demo.q}</p>
            <div
              key={pick}
              className={cx(
                "ia-pop max-w-[88%] self-start rounded-[20px] rounded-bl-md border-2 border-ink px-4 py-3.5 text-[16px] leading-relaxed",
                demo.grounded ? "bg-cream" : "bg-[#fffbf2]",
              )}
            >
              {demo.a}
              <div className="mt-2.5 flex flex-wrap gap-1.5">
                <Chip className="bg-[#f3ece4] text-[#5f5249]">{demo.source}</Chip>
                {demo.grounded ? (
                  <Chip className="bg-ok-soft text-[#256640]">Grounded</Chip>
                ) : (
                  <Chip className="bg-clay-soft text-clay-strong">Handed to a person</Chip>
                )}
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ---------------------------------------------------------------- cta */}
      <section className="relative overflow-hidden border-t-2 border-ink bg-clay-soft py-28">
        <Shape className="left-[8%] top-[20%] h-[26px] w-[26px] rounded-full bg-accent" />
        <Shape className="right-[10%] top-[14%] h-[34px] w-[34px] bg-clay [clip-path:polygon(50%_0,100%_100%,0_100%)] [animation-delay:-3s]" />
        <Shape className="bottom-[14%] right-[22%] h-10 w-10 rounded-full border-[5px] border-ink [animation-delay:-1s]" />
        <Shape className="bottom-[18%] left-[18%] h-[30px] w-[30px] rounded-lg bg-sage [animation-delay:-5s]" style={vars({ "--r": "30deg" })} />
        <div className="ia-reveal relative mx-auto flex max-w-[1200px] flex-col items-center gap-[22px] px-6 text-center">
          <Inti pose="cheer" className="h-[162px] w-[150px]" />
          <h2 className="font-display text-[clamp(40px,5vw,68px)] font-semibold leading-[1.02] tracking-[-0.02em]">
            Start free. Stay in control.
          </h2>
          <p className="max-w-[560px] text-[19px] leading-relaxed text-muted">
            IntelliAssist runs on your own infrastructure with your own model key, so you pay only for the model usage
            you consume. Test every answer in the playground before anyone sees it.
          </p>
          <div className="mt-2.5 flex flex-wrap justify-center gap-4">
            <PillLink href={startHref} variant="primary">
              {user ? "Open dashboard" : "Create your account"}
            </PillLink>
            {!user && (
              <PillLink href="/login" variant="secondary">
                Sign in
              </PillLink>
            )}
          </div>
        </div>
      </section>

      <footer className="bg-ink text-[#e9e0d6]">
        <div className="mx-auto flex min-h-[88px] max-w-[1200px] flex-wrap items-center gap-6 px-6 text-[14px]">
          <span className="font-display text-[18px] font-semibold text-white">IntelliAssist</span>
          <span className="flex-1">Answers grounded in your documents. Never invented.</span>
          <a href="#learn" className="hover:text-white">How it works</a>
          {!user && (
            <Link href="/login" className="hover:text-white">
              Sign in
            </Link>
          )}
          <Link href={startHref} className="text-[#f3c3ae] hover:text-white">
            {user ? "Open dashboard" : "Start free"}
          </Link>
        </div>
      </footer>
    </div>
  );
}

/* ------------------------------------------------------------ pieces */

function PillLink({
  href,
  variant,
  small = false,
  children,
}: {
  href: string;
  variant: "primary" | "secondary";
  small?: boolean;
  children: ReactNode;
}) {
  const className = cx(
    "inline-flex items-center justify-center gap-2.5 whitespace-nowrap rounded-full border-2 font-semibold transition-[transform,box-shadow] duration-200 hover:-translate-y-[3px] hover:-rotate-1",
    small ? "min-h-11 px-5 text-[15px]" : "min-h-[52px] px-6 text-[16px]",
    variant === "primary"
      ? "border-transparent bg-accent text-white shadow-[0_6px_0_var(--color-accent-strong)] hover:shadow-[0_9px_0_var(--color-accent-strong)]"
      : "border-ink bg-surface text-ink shadow-[0_6px_0_var(--color-ink)] hover:shadow-[0_9px_0_var(--color-ink)]",
  );
  // In-page anchors stay plain links; routes go through Next.
  return href.startsWith("#") ? (
    <a href={href} className={className}>{children}</a>
  ) : (
    <Link href={href} className={className}>{children}</Link>
  );
}

function Kicker({ children, light = false }: { children: ReactNode; light?: boolean }) {
  return (
    <p className={cx("mb-4 inline-flex items-center gap-2 text-[13px] font-semibold uppercase tracking-[0.08em]", light ? "text-[#f3c3ae]" : "text-clay-strong")}>
      <span className={cx("h-[3px] w-7 rounded", light ? "bg-[#f3c3ae]" : "bg-clay")} aria-hidden />
      {children}
    </p>
  );
}

function ChapterTitle({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <h2 className={cx("font-display text-[clamp(36px,4vw,54px)] font-semibold leading-[1.05] tracking-[-0.02em]", className)}>
      {children}
    </h2>
  );
}

function Chip({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span className={cx("inline-flex items-center whitespace-nowrap rounded-full px-[11px] py-1 text-[12px] font-semibold", className)}>
      {children}
    </span>
  );
}

function Shape({ className, style }: { className: string; style?: CSSProperties }) {
  return <div className={cx("ia-float pointer-events-none absolute", className)} style={style} aria-hidden />;
}

function Floating({ className, style, children }: { className: string; style?: CSSProperties; children: ReactNode }) {
  return (
    <div className={cx("ia-float absolute", className)} style={style}>
      {children}
    </div>
  );
}

function Bubble({ side, className, children }: { side: "left" | "right"; className?: string; children: ReactNode }) {
  return (
    <div
      className={cx(
        "rounded-[18px] border-2 border-ink bg-surface px-3.5 py-2.5 text-[14px] font-semibold shadow-[4px_4px_0_var(--color-ink)]",
        side === "left" ? "rounded-bl-[4px]" : "rounded-br-[4px]",
        className,
      )}
    >
      {children}
    </div>
  );
}

function StarPath({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden>
      <path d="M12 1l2.6 8.4L23 12l-8.4 2.6L12 23l-2.6-8.4L1 12l8.4-2.6z" fill="currentColor" />
    </svg>
  );
}

function Sparkle({ className }: { className: string }) {
  return (
    <span className={cx("ia-twinkle pointer-events-none absolute", className)} aria-hidden>
      <StarPath className="h-full w-full" />
    </span>
  );
}

function FlyingDoc({
  className,
  tx,
  ty,
  bar,
  label,
  tint = false,
}: {
  className: string;
  tx: string;
  ty: string;
  bar: string;
  label: string;
  tint?: boolean;
}) {
  return (
    <div className={cx("ia-fly absolute", className)} style={vars({ "--tx": tx, "--ty": ty })}>
      <div className={cx("flex h-[98px] w-[78px] flex-col gap-1.5 rounded-[10px] border-2 border-ink p-2.5", tint ? "bg-clay-soft" : "bg-surface")}>
        <span className={cx("h-2 w-[70%] rounded", bar)} />
        <span className="h-[5px] rounded bg-[#d9c6b3]" />
        <span className="h-[5px] w-4/5 rounded bg-[#d9c6b3]" />
        <span className="mt-auto text-[11px] font-bold">{label}</span>
      </div>
    </div>
  );
}

function Teammate() {
  return (
    <svg viewBox="0 0 120 120" className="h-[120px] w-[120px]">
      <g className="ia-bob [animation-delay:-1.8s]">
        <circle cx="60" cy="60" r="56" fill="#fff" stroke="var(--color-ink)" strokeWidth="3" />
        <circle cx="60" cy="50" r="22" fill="#c98a5e" />
        <path d="M36 46 q4 -26 24 -26 q20 0 24 26 q-10 -12 -24 -12 q-14 0 -24 12z" fill="var(--color-ink)" />
        <path d="M26 106 q8 -30 34 -30 q26 0 34 30" fill="var(--color-accent)" />
        <path d="M34 50 q0 -30 26 -30 q26 0 26 30" fill="none" stroke="var(--color-clay)" strokeWidth="5" />
        <rect x="28" y="46" width="10" height="16" rx="4" fill="var(--color-clay)" />
        <circle cx="52" cy="52" r="2.5" fill="var(--color-ink)" />
        <circle cx="68" cy="52" r="2.5" fill="var(--color-ink)" />
        <path d="M53 60 q7 6 14 0" stroke="var(--color-ink)" strokeWidth="2.5" fill="none" strokeLinecap="round" />
      </g>
    </svg>
  );
}
