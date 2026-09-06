"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Bot } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { Loading } from "@/components/ui";

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
    <main className="flex min-h-dvh flex-col items-center justify-center px-4 py-12">
      <div className="mb-7 flex items-center gap-2.5">
        <span className="grid h-9 w-9 place-items-center rounded-lg bg-accent text-white">
          <Bot className="h-5 w-5" />
        </span>
        <span className="text-lg font-semibold tracking-tight">IntelliAssist</span>
      </div>

      <div className="w-full max-w-sm rounded-xl border border-line bg-surface p-6">{children}</div>
    </main>
  );
}
