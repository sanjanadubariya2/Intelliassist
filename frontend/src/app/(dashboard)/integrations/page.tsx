"use client";

import Link from "next/link";
import { Code2, Globe, Puzzle } from "lucide-react";
import { Badge, Card, CardHeader, PageHeader } from "@/components/ui";

/**
 * Integrations.
 *
 * Only two of these exist, and they are the ones that matter: the embeddable
 * widget and the API behind it. The rest are listed as not built rather than
 * shown as toggles that do nothing — a switch that silently fails is worse
 * than an absent feature, because someone will believe it worked.
 */
const AVAILABLE = [
  {
    name: "Website widget",
    description:
      "One script tag on any page. Streams answers with citations, isolated in an iframe so your CSS and ours cannot collide.",
    icon: <Code2 className="h-5 w-5" />,
    href: "/studio?tab=install",
  },
  {
    name: "Public REST API",
    description:
      "The endpoints the widget itself calls. Authenticated by a bot key and restricted to your allowed domains.",
    icon: <Globe className="h-5 w-5" />,
    href: "/studio?tab=install",
  },
];

const NOT_BUILT = ["Slack", "Microsoft Teams", "Zendesk", "Intercom", "WhatsApp", "Webhooks"];

export default function IntegrationsPage() {
  return (
    <>
      <PageHeader title="Integrations" description="Where your assistants can be reached from." />

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {AVAILABLE.map((integration) => (
          <Card key={integration.name} className="p-5">
            <div className="flex items-start gap-3">
              <span className="grid h-10 w-10 shrink-0 place-items-center rounded-lg bg-clay-soft text-clay">
                {integration.icon}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <h2 className="text-sm font-semibold">{integration.name}</h2>
                  <Badge tone="ok">Available</Badge>
                </div>
                <p className="mt-1 text-[13px] leading-relaxed text-muted">
                  {integration.description}
                </p>
                <Link
                  href={integration.href}
                  className="mt-3 inline-block text-[13px] font-medium text-clay hover:underline"
                >
                  Set up
                </Link>
              </div>
            </div>
          </Card>
        ))}
      </div>

      <Card className="mt-4">
        <CardHeader
          title="Not built"
          description="Listed so you can see where the edges are, rather than shown as switches that do nothing."
        />
        <div className="flex flex-wrap gap-2 p-5">
          {NOT_BUILT.map((name) => (
            <span
              key={name}
              className="rounded-lg border border-line px-3 py-1.5 text-[13px] text-faint"
            >
              {name}
            </span>
          ))}
        </div>
      </Card>

      <Card className="mt-4">
        <div className="flex items-start gap-3 p-5">
          <Puzzle className="mt-0.5 h-5 w-5 shrink-0 text-faint" />
          <p className="text-[13px] leading-relaxed text-muted">
            Each of those is a real piece of work — an OAuth flow, a message format, and
            somewhere for delivery failures to go. The widget and the REST API cover the case
            this product is actually for, so they came first.
          </p>
        </div>
      </Card>
    </>
  );
}
