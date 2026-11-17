"use client";

import { useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Check, FileText } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { HeroIllustration, LeafSprig } from "@/components/illustration";
import { Loading, Logo, LogoMark } from "@/components/ui";

/**
 * The signed-out half of the app.
 *
 * Someone already signed in has no business here, so they are sent onward
 * rather than shown a login form they would only have to dismiss.
 */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && user) router.replace("/");
  }, [loading, user, router]);

  if (loading || user) return <Loading />;

  return (
    <div className="grid min-h-dvh grid-cols-1 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <main className="relative flex flex-col px-4 py-6 sm:px-10">
        <Link href="/welcome" className="w-fit" aria-label="IntelliAssist home">
          <Logo />
        </Link>
        <div className="flex flex-1 items-center justify-center py-10">
          <div className="animate-rise w-full max-w-sm rounded-3xl border border-line bg-surface p-7 shadow-lift">
            {children}
          </div>
        </div>
        <LeafSprig className="pointer-events-none absolute bottom-0 left-0 w-20 opacity-70" />
      </main>

      <aside className="relative hidden overflow-hidden bg-[linear-gradient(160deg,#f8ebe1,#f3e1d3)] lg:flex lg:flex-col lg:justify-center lg:px-14">
        <h2 className="font-display text-[40px] font-semibold leading-[1.1] tracking-tight">
          Answers from <em className="squiggle font-medium text-clay">your</em> documents.
          <br />
          Never invented.
        </h2>
        <ul className="mt-6 space-y-2.5 text-[15px] text-ink/80">
          {[
            "Every answer cites the document it came from",
            "Declines politely when it doesn't know",
            "Hands tricky conversations to your team",
          ].map((line) => (
            <li key={line} className="flex items-center gap-2.5">
              <span className="grid h-5 w-5 place-items-center rounded-full bg-surface text-clay">
                <Check className="h-3 w-3" strokeWidth={3} />
              </span>
              {line}
            </li>
          ))}
        </ul>

        <div className="mt-10 grid grid-cols-[1fr_auto] items-end gap-4">
          <HeroIllustration className="w-full max-w-[360px]" />
          <div className="mb-10 w-56 rounded-2xl border border-line bg-surface p-3 text-[12px] shadow-lift">
            <div className="flex items-start gap-2">
              <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-accent text-white">
                <LogoMark className="h-3 w-3 text-white" />
              </span>
              <p>Refunds are accepted within 30 days of delivery.</p>
            </div>
            <span className="mt-2 inline-flex items-center gap-1 rounded-md bg-clay-soft px-1.5 py-0.5 text-[10.5px]">
              <FileText className="h-2.5 w-2.5 text-clay" /> Refund_Policy.pdf p.1
            </span>
          </div>
        </div>
      </aside>
    </div>
  );
}
