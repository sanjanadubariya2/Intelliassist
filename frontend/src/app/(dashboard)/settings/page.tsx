"use client";

import { useAuth } from "@/lib/auth";
import { Avatar, Badge, Card, Loading, PageHeader } from "@/components/ui";

const SECTIONS = [
  { id: "account", label: "Your account" },
  { id: "organization", label: "Organization" },
  { id: "security", label: "Security" },
];

/**
 * The organization and account, read-only.
 *
 * Renaming an organization changes its slug, which appears in URLs, and
 * changing an email needs a verification round trip. Both are real features
 * rather than one more form, and a form that silently fails would be worse
 * than not offering one.
 */
export default function SettingsPage() {
  const { user } = useAuth();
  if (!user) return <Loading />;

  const role = user.role[0] + user.role.slice(1).toLowerCase();

  return (
    <div className="flex flex-wrap items-start gap-8">
      <nav aria-label="Settings sections" className="flex min-w-0 flex-[1_1_200px] flex-col gap-0.5 lg:sticky lg:top-24">
        <PageHeader title="Settings" />
        {SECTIONS.map((section, index) => (
          <a
            key={section.id}
            href={`#${section.id}`}
            className={
              index === 0
                ? "flex min-h-10 items-center rounded-xl bg-clay-soft px-3 text-[14px] font-semibold text-clay-strong"
                : "flex min-h-10 items-center rounded-xl px-3 text-[14px] font-medium text-[#4a3d35] hover:bg-cream"
            }
          >
            {section.label}
          </a>
        ))}
      </nav>

      <div className="flex min-w-0 flex-[999_1_520px] flex-col gap-5 lg:pt-[68px]">
        <Card className="scroll-mt-24 p-6" >
          <section id="account" className="flex flex-col gap-5">
            <h2 className="font-display text-[20px] font-semibold">Your account</h2>
            <div className="flex items-center gap-4">
              <Avatar name={user.user.name} className="h-16 w-16 text-[20px]" />
              <div>
                <p className="text-[16px] font-semibold">{user.user.name}</p>
                <p className="text-[14px] text-muted">{user.user.email}</p>
              </div>
            </div>
            <dl className="grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))] gap-4">
              <ReadOnly label="Full name" value={user.user.name} />
              <ReadOnly label="Email" value={user.user.email} />
              <ReadOnly label="Role" value={<Badge tone="accent">{role}</Badge>} />
            </dl>
            <p className="text-[13px] text-muted">
              Changing your name or email isn&apos;t available yet: an email change needs a verification step first.
            </p>
          </section>
        </Card>

        <Card className="p-6">
          <section id="organization" className="flex scroll-mt-24 flex-col gap-5">
            <h2 className="font-display text-[20px] font-semibold">Organization</h2>
            <dl className="grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))] gap-4">
              <ReadOnly label="Workspace name" value={user.organization.name} />
              <ReadOnly label="Slug" value={<span className="font-mono text-[13px]">{user.organization.slug}</span>} />
            </dl>
            <p className="text-[13px] text-muted">
              Renaming changes the slug, which appears in URLs, so it isn&apos;t offered as a simple edit.
            </p>
          </section>
        </Card>

        <Card className="p-6">
          <section id="security" className="flex scroll-mt-24 flex-col gap-3">
            <h2 className="font-display text-[20px] font-semibold">Security</h2>
            <ul className="divide-y divide-[#f3ece4] text-[14px]">
              {[
                ["Passwords", "Hashed with BCrypt and never stored in readable form."],
                ["Sessions", "A signed token that expires after 12 hours."],
                ["Single sign-on", "Google, GitHub and LinkedIn, when configured on the server."],
                ["Widget keys", "Stored only as a SHA-256 hash and checked against each bot’s allowed domains."],
                ["Documents", "Only ever reachable by bots in this organization."],
              ].map(([title, body]) => (
                <li key={title} className="flex flex-wrap gap-x-4 gap-y-1 py-3.5">
                  <span className="w-36 shrink-0 font-semibold">{title}</span>
                  <span className="min-w-0 flex-1 text-muted">{body}</span>
                </li>
              ))}
            </ul>
          </section>
        </Card>
      </div>
    </div>
  );
}

function ReadOnly({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <dt className="text-[14px] font-semibold">{label}</dt>
      <dd className="flex min-h-11 items-center rounded-xl border border-[#e2d4c5] bg-cream px-3.5 text-[14px]">
        {value}
      </dd>
    </div>
  );
}
