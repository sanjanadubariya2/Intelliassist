"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Check } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { Inti, IntiMark } from "@/components/inti";
import { Loading } from "@/components/ui";

/**
 * The signed-out half of the app: the form on the left, a brand panel on the
 * right (sign in shows what the product promises, sign up shows what happens
 * next). The panel drops away on narrow screens.
 *
 * Someone already signed in has no business here, so they are sent onward
 * rather than shown a login form they would only have to dismiss.
 */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const signingUp = pathname.startsWith("/register");

  useEffect(() => {
    if (!loading && user) router.replace("/");
  }, [loading, user, router]);

  if (loading || user) return <Loading />;

  return (
    <div className="ia-motion flex min-h-dvh flex-wrap bg-canvas">
      <main className="flex min-w-0 flex-[1_1_480px] flex-col px-6 py-8">
        <Link href="/welcome" aria-label="IntelliAssist home" className="flex w-fit items-center gap-2.5">
          <IntiMark className="h-9 w-9" />
          <span className="font-display text-[20px] font-semibold tracking-tight">IntelliAssist</span>
        </Link>
        <div className="flex flex-1 items-center justify-center py-12">
          <div className="animate-rise w-full max-w-[420px]">{children}</div>
        </div>
      </main>

      {signingUp ? <NextStepsPanel /> : <PromisePanel />}
    </div>
  );
}

function PromisePanel() {
  return (
    <aside className="relative hidden min-h-[420px] flex-[1_1_420px] flex-col justify-center gap-7 overflow-hidden bg-accent px-12 py-14 text-[#f6efe7] lg:flex">
      <div className="ia-spin absolute -right-24 -top-24 h-72 w-72 rounded-full border-[3px] border-dashed border-white/20" aria-hidden />
      <p className="text-[13px] font-semibold uppercase tracking-[0.08em] text-[#f3c3ae]">Welcome back</p>
      <Inti pose="wave" className="h-auto w-44" />
      <p className="max-w-md font-display text-[30px] font-semibold leading-tight text-white">
        Answers grounded in your documents. Never invented.
      </p>
      <ul className="max-w-md space-y-3 text-[15px]">
        {[
          "Every answer names the document it came from",
          "Says so when it doesn’t know",
          "Hands tricky conversations to your team",
        ].map((line) => (
          <li key={line} className="flex items-center gap-3">
            <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-white/10 text-[#f3c3ae]">
              <Check className="h-3.5 w-3.5" strokeWidth={3} />
            </span>
            {line}
          </li>
        ))}
      </ul>
    </aside>
  );
}

const STEPS = [
  { title: "Name your first bot", body: "A friendly name and a greeting." },
  { title: "Upload a few documents", body: "Policies, FAQs, a product guide. PDF, DOCX, TXT or Markdown." },
  { title: "Try it in the playground", body: "See the source behind every answer." },
  { title: "Go live", body: "Allow your domain and paste one script tag." },
];

function NextStepsPanel() {
  return (
    <aside className="hidden min-h-[420px] flex-[1_1_420px] flex-col justify-center gap-6 bg-clay-soft px-12 py-14 lg:flex">
      <p className="text-[13px] font-semibold uppercase tracking-[0.08em] text-clay-strong">What happens next</p>
      <ol className="max-w-md space-y-4">
        {STEPS.map((step, index) => (
          <li key={step.title} className="flex items-start gap-4 rounded-2xl bg-surface px-5 py-4">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-accent font-display text-[16px] font-semibold text-white">
              {index + 1}
            </span>
            <span>
              <span className="block text-[16px] font-semibold">{step.title}</span>
              <span className="text-[14px] leading-normal text-muted">{step.body}</span>
            </span>
          </li>
        ))}
      </ol>
    </aside>
  );
}
