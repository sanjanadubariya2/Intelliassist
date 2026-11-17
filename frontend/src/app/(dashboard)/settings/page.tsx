"use client";

import { Building2, KeyRound, Mail, ShieldCheck } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { Badge, Card, CardHeader, Loading, PageHeader } from "@/components/ui";

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

  return (
    <>
      <PageHeader title="Settings" description="Your organization and account." />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title="Organization" />
          <dl className="divide-y divide-line">
            <Row icon={<Building2 className="h-4 w-4" />} label="Name" value={user.organization.name} />
            <Row
              icon={<KeyRound className="h-4 w-4" />}
              label="Slug"
              value={user.organization.slug}
              mono
            />
          </dl>
        </Card>

        <Card>
          <CardHeader title="Your account" />
          <dl className="divide-y divide-line">
            <Row icon={<Mail className="h-4 w-4" />} label="Name" value={user.user.name} />
            <Row icon={<Mail className="h-4 w-4" />} label="Email" value={user.user.email} />
            <Row
              icon={<ShieldCheck className="h-4 w-4" />}
              label="Role"
              value={<Badge tone="accent">{user.role[0] + user.role.slice(1).toLowerCase()}</Badge>}
            />
          </dl>
        </Card>
      </div>

      <Card className="mt-4">
        <CardHeader
          title="Security"
          description="How this workspace is protected."
        />
        <ul className="space-y-2 p-5 text-[13px] text-muted">
          <li>• Passwords are hashed with BCrypt and never stored in readable form.</li>
          <li>• Dashboard sessions use a signed token that expires after 12 hours.</li>
          <li>
            • Widget keys are stored only as a SHA-256 hash and are checked against each
            bot&apos;s allowed domains on every request.
          </li>
          <li>
            • Your documents are only ever reachable by bots in this organization.
          </li>
        </ul>
      </Card>
    </>
  );
}

function Row({
  icon,
  label,
  value,
  mono,
}: {
  icon: React.ReactNode;
  label: string;
  value: React.ReactNode;
  mono?: boolean;
}) {
  return (
    <div className="flex items-center gap-3 px-5 py-3.5">
      <span className="text-faint">{icon}</span>
      <dt className="w-24 shrink-0 text-[13px] text-muted">{label}</dt>
      <dd className={mono ? "font-mono text-[13px]" : "text-sm"}>{value}</dd>
    </div>
  );
}
