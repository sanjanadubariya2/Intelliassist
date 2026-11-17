"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { Inti } from "@/components/inti";

/**
 * Where the backend sends the browser after a successful single sign-on.
 *
 * The token arrives in the URL fragment, which browsers never send to a
 * server, so it stays out of access logs and Referer headers. It is read once,
 * then wiped from the address bar before anything else can see it.
 */
export default function SsoCallbackPage() {
  const { signInWithToken } = useAuth();
  const router = useRouter();
  const [failed, setFailed] = useState(false);
  // React runs effects twice in development; the token must be used once.
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return;
    handled.current = true;

    const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
    window.history.replaceState(null, "", window.location.pathname);

    if (!token) {
      setFailed(true);
      return;
    }
    signInWithToken(token)
      .then(() => router.replace("/"))
      .catch(() => setFailed(true));
  }, [signInWithToken, router]);

  return (
    <main className="ia-motion grid min-h-dvh place-items-center bg-canvas px-6 text-center">
      <div className="flex flex-col items-center gap-5">
        <Inti pose={failed ? "puzzled" : "wave"} className="h-auto w-36" />
        {failed ? (
          <>
            <h1 className="font-display text-[28px] font-semibold">That sign-in didn’t work</h1>
            <p className="max-w-sm text-muted">The link was incomplete or has expired. Please try again.</p>
            <Link
              href="/login"
              className="inline-flex min-h-11 items-center rounded-xl bg-accent px-5 text-sm font-semibold text-white hover:bg-accent-strong"
            >
              Back to sign in
            </Link>
          </>
        ) : (
          <>
            <h1 className="font-display text-[28px] font-semibold">Signing you in…</h1>
            <p className="text-muted" role="status">
              Inti is opening your workspace.
            </p>
          </>
        )}
      </div>
    </main>
  );
}
