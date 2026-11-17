"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Field, Input } from "@/components/ui";

export default function LoginPage() {
  const { signIn } = useAuth();
  const router = useRouter();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [expired, setExpired] = useState(false);

  // Set by the API client when a token runs out mid-session. Read from
  // location in an effect rather than useSearchParams, which would need a
  // Suspense boundary around the whole form.
  useEffect(() => {
    setExpired(new URLSearchParams(window.location.search).has("expired"));
  }, []);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);

    try {
      await signIn(email, password);
      router.replace("/");
    } catch (caught) {
      // The backend answers a bad email and a bad password identically, so
      // this message stays vague on purpose: saying which one was wrong would
      // confirm whether an account exists.
      setError(
        caught instanceof ApiError && caught.isUnauthorized
          ? "That email and password do not match an account."
          : caught instanceof Error
            ? // Covers the unreachable-API case, whose message says so rather
              // than implying the credentials were wrong.
              caught.message
            : "Could not sign in.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1 className="font-display text-[26px] font-semibold tracking-tight">Sign in</h1>
      <p className="mt-1 mb-5 text-[13px] text-muted">Welcome back.</p>

      {expired && (
        <p className="mb-4 rounded-xl bg-warn-soft px-3 py-2 text-[13px] text-warn" role="status">
          Your session expired. Sign in again to pick up where you left off.
        </p>
      )}

      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Email">
          <Input
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@company.com"
          />
        </Field>

        <Field label="Password">
          <Input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            placeholder="••••••••"
          />
        </Field>

        {error && (
          <p className="rounded-lg bg-bad-soft px-3 py-2 text-[13px] text-bad" role="alert">
            {error}
          </p>
        )}

        <Button type="submit" loading={busy} className="w-full">
          Sign in
        </Button>
      </form>

      <p className="mt-5 text-center text-[13px] text-muted">
        No account?{" "}
        <Link href="/register" className="font-medium text-clay hover:underline">
          Create one
        </Link>
      </p>
    </>
  );
}
