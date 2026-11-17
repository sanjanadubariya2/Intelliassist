"use client";

import { useEffect, useState } from "react";
import { api, ssoStartUrl, type SsoProvider, type SsoProviderId } from "@/lib/api";
import { cx } from "@/components/ui";

const ORDER: SsoProviderId[] = ["google", "github", "linkedin"];
const NAMES: Record<SsoProviderId, string> = { google: "Google", github: "GitHub", linkedin: "LinkedIn" };

/**
 * "Continue with Google / GitHub / LinkedIn".
 *
 * Each button is a plain link to the backend, which redirects to the provider.
 * Providers the server has not been configured for are shown but disabled, so
 * the option is visible and the reason is stated rather than failing on click.
 */
export function SsoButtons({ verb }: { verb: "Continue" | "Sign up" }) {
  // Until the server answers, assume all are available; a wrong guess only
  // costs a redirect back to the login page with a clear message.
  const [providers, setProviders] = useState<SsoProvider[] | null>(null);
  // Read after mount, so server and browser render the same link first.
  const [origin, setOrigin] = useState<string | undefined>(undefined);
  useEffect(() => setOrigin(window.location.origin), []);

  useEffect(() => {
    let cancelled = false;
    api.auth
      .providers()
      .then((result) => !cancelled && setProviders(result.providers))
      .catch(() => !cancelled && setProviders(null));
    return () => {
      cancelled = true;
    };
  }, []);

  const enabled = (id: SsoProviderId) => providers?.find((p) => p.id === id)?.enabled ?? true;
  const anyDisabled = providers?.some((p) => !p.enabled) ?? false;

  return (
    <div className="space-y-2.5" role="group" aria-label={`${verb} with another account`}>
      {ORDER.map((id) => {
        const on = enabled(id);
        const label = `${verb} with ${NAMES[id]}`;
        const className = cx(
          "flex min-h-12 w-full items-center justify-center gap-3 rounded-xl border border-[#e2d4c5] bg-surface px-4 text-[15px] font-semibold text-ink transition-colors",
          on ? "hover:border-[#cdb9a5] hover:bg-cream" : "cursor-not-allowed opacity-50",
        );
        return on ? (
          <a key={id} href={ssoStartUrl(id, origin)} className={className}>
            <ProviderLogo id={id} />
            {label}
          </a>
        ) : (
          <span key={id} className={className} aria-disabled title={`${NAMES[id]} sign-in isn’t set up on this server`}>
            <ProviderLogo id={id} />
            {label}
          </span>
        );
      })}
      {anyDisabled && (
        <p className="text-center text-[12px] text-muted">Greyed-out options aren’t set up on this server yet.</p>
      )}
    </div>
  );
}

/** A divider between the provider buttons and the email form. */
export function OrDivider({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-3.5 text-[13px] text-muted" aria-hidden>
      <span className="h-px flex-1 bg-[#e2d4c5]" />
      {children}
      <span className="h-px flex-1 bg-[#e2d4c5]" />
    </div>
  );
}

/**
 * Simplified provider marks. Before launch, swap these for each provider's
 * official button artwork; all three publish branding rules for sign-in.
 */
function ProviderLogo({ id }: { id: SsoProviderId }) {
  if (id === "google") {
    return (
      <svg viewBox="0 0 24 24" className="h-5 w-5" aria-hidden>
        <path d="M21.6 12.2c0-.7-.1-1.4-.2-2H12v3.9h5.4a4.6 4.6 0 0 1-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.4z" fill="#4285F4" />
        <path d="M12 22c2.7 0 5-.9 6.6-2.4l-3.2-2.5c-.9.6-2 1-3.4 1-2.6 0-4.8-1.8-5.6-4.1H3.1v2.6A10 10 0 0 0 12 22z" fill="#34A853" />
        <path d="M6.4 14c-.2-.6-.3-1.3-.3-2s.1-1.4.3-2V7.4H3.1a10 10 0 0 0 0 9.2L6.4 14z" fill="#FBBC05" />
        <path d="M12 5.9c1.5 0 2.8.5 3.8 1.5l2.9-2.9A10 10 0 0 0 3.1 7.4L6.4 10c.8-2.3 3-4.1 5.6-4.1z" fill="#EA4335" />
      </svg>
    );
  }
  if (id === "github") {
    return (
      <svg viewBox="0 0 24 24" className="h-5 w-5" aria-hidden>
        <path
          fill="#24292f"
          d="M12 2a10 10 0 0 0-3.2 19.5c.5.1.7-.2.7-.5v-1.7c-2.8.6-3.4-1.2-3.4-1.2-.4-1.2-1.1-1.5-1.1-1.5-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.4 1.1 2.9.8.1-.6.4-1.1.6-1.3-2.2-.3-4.6-1.1-4.6-5 0-1.1.4-2 1-2.7-.1-.3-.4-1.3.1-2.7 0 0 .8-.3 2.8 1a9.6 9.6 0 0 1 5 0c1.9-1.3 2.8-1 2.8-1 .5 1.4.2 2.4.1 2.7.6.7 1 1.6 1 2.7 0 3.9-2.3 4.7-4.6 4.9.4.3.7.9.7 1.9V21c0 .3.2.6.7.5A10 10 0 0 0 12 2z"
        />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 24 24" className="h-5 w-5" aria-hidden>
      <rect width="24" height="24" rx="4" fill="#0A66C2" />
      <path
        fill="#fff"
        d="M7 9.5v8H4.6v-8H7zM5.8 5.6a1.4 1.4 0 1 1 0 2.8 1.4 1.4 0 0 1 0-2.8zM9.6 9.5h2.3v1.1c.3-.6 1.1-1.3 2.4-1.3 2.5 0 3 1.6 3 3.8v4.4h-2.4v-3.9c0-.9 0-2.1-1.3-2.1s-1.5 1-1.5 2v4H9.6v-8z"
      />
    </svg>
  );
}
