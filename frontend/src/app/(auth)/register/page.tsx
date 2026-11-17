"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Field, Input } from "@/components/ui";

/** Mirrors the @Size on RegisterRequest.password, so the server never has to say no. */
const MIN_PASSWORD_LENGTH = 12;

export default function RegisterPage() {
  const { signUp } = useAuth();
  const router = useRouter();

  const [form, setForm] = useState({
    name: "",
    email: "",
    password: "",
    organizationName: "",
  });
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  function update(key: keyof typeof form) {
    return (event: React.ChangeEvent<HTMLInputElement>) =>
      setForm((current) => ({ ...current, [key]: event.target.value }));
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFieldErrors({});

    try {
      await signUp(form);
      router.replace("/");
    } catch (caught) {
      if (caught instanceof ApiError) {
        // Bean Validation returns per-field messages; showing them beside the
        // field beats one generic banner the user has to decode. A server we
        // never reached has no field errors, so it falls through to its own
        // message, which names the real problem.
        if (caught.fieldErrors && !caught.isUnreachable) setFieldErrors(caught.fieldErrors);
        else setError(caught.message);
      } else {
        setError("Could not create the account.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1 className="font-display text-[26px] font-semibold tracking-tight">Create your workspace</h1>
      <p className="mt-1 mb-5 text-[13px] text-muted">
        Your organization and your first account, in one step.
      </p>

      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Your name" error={fieldErrors.name}>
          <Input required autoComplete="name" value={form.name} onChange={update("name")} />
        </Field>

        <Field label="Work email" error={fieldErrors.email}>
          <Input
            type="email"
            required
            autoComplete="email"
            value={form.email}
            onChange={update("email")}
            placeholder="you@company.com"
          />
        </Field>

        <Field label="Organization" error={fieldErrors.organizationName}>
          <Input
            required
            value={form.organizationName}
            onChange={update("organizationName")}
            placeholder="Acme Inc."
          />
        </Field>

        <Field
          label="Password"
          hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
          error={fieldErrors.password}
        >
          <Input
            type="password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            autoComplete="new-password"
            value={form.password}
            onChange={update("password")}
          />
        </Field>

        {error && (
          <p className="rounded-lg bg-bad-soft px-3 py-2 text-[13px] text-bad" role="alert">
            {error}
          </p>
        )}

        <Button type="submit" loading={busy} className="w-full">
          Create account
        </Button>
      </form>

      <p className="mt-5 text-center text-[13px] text-muted">
        Already have an account?{" "}
        <Link href="/login" className="font-medium text-clay hover:underline">
          Sign in
        </Link>
      </p>
    </>
  );
}
