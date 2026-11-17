"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { ApiError, SSO_ERRORS } from "@/lib/api";
import { Button, Field, Input } from "@/components/ui";
import { OrDivider, SsoButtons } from "@/components/sso-buttons";

export default function LoginPage() {
  const { signIn } = useAuth();
  const router = useRouter();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [expired, setExpired] = useState(false);
  const [ssoError, setSsoError] = useState<string | null>(null);

  // `expired` is set by the API client when a token runs out mid-session, and
  // `sso_error` by the backend when a provider sign-in fails. Read from
  // location in an effect rather than useSearchParams, which would need a
  // Suspense boundary around the whole form.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    setExpired(params.has("expired"));
    const code = params.get("sso_error");
    if (code) setSsoError(SSO_ERRORS[code] ?? "Sign-in with that provider didn't work. Please try again.");
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
      <h1 className="font-display text-[38px] font-semibold leading-tight tracking-tight">Welcome back</h1>
      <p className="mt-2 mb-6 text-[16px] text-muted">Sign in to your workspace.</p>

      {expired && (
        <p className="mb-4 rounded-xl bg-warn-soft px-3 py-2 text-[13px] text-warn" role="status">
          Your session expired. Sign in again to pick up where you left off.
        </p>
      )}
      {ssoError && (
        <p className="mb-4 rounded-xl bg-bad-soft px-3 py-2 text-[13px] text-bad" role="alert">
          {ssoError}
        </p>
      )}

      <SsoButtons verb="Continue" />
      <div className="my-6">
        <OrDivider>or sign in with email</OrDivider>
      </div>

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

      <p className="mt-6 text-center text-[14px] text-muted">
        New to IntelliAssist?{" "}
        <Link href="/register" className="font-semibold text-accent hover:underline">
          Create an account
        </Link>
      </p>
    </>
  );
}
