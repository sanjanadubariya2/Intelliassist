"use client";

import Link from "next/link";
import { Badge, PageHeader, cx } from "@/components/ui";

/**
 * Integrations.
 *
 * Only two of these exist, and they are the ones that matter: the embeddable
 * widget and the API behind it. The rest are shown as "not built" cards with
 * no button, rather than as toggles that do nothing: a switch that silently
 * fails is worse than an absent feature, because someone will believe it
 * worked.
 */
interface Integration {
  mono: string;
  name: string;
  description: string;
  /** Present only for integrations that exist. */
  href?: string;
}

const GROUPS: Array<{ title: string; items: Integration[] }> = [
  {
    title: "Channels",
    items: [
      {
        mono: "W",
        name: "Website widget",
        description:
          "One script tag on any page. Streams answers with citations, isolated in an iframe so your CSS and ours cannot collide.",
        href: "/studio?tab=install",
      },
      { mono: "S", name: "Slack", description: "Answer questions in a shared channel." },
      { mono: "W", name: "WhatsApp", description: "Reply to customers on your WhatsApp Business number." },
      { mono: "T", name: "Microsoft Teams", description: "Let staff ask the bot inside Teams." },
    ],
  },
  {
    title: "Helpdesk and developer",
    items: [
      {
        mono: "A",
        name: "Public REST API",
        description:
          "The endpoints the widget itself calls. Authenticated by a bot key and restricted to your allowed domains.",
        href: "/studio?tab=install",
      },
      { mono: "I", name: "Intercom", description: "Hand conversations over to Intercom when a person is needed." },
      { mono: "Z", name: "Zendesk", description: "Create a ticket for every handoff, with the transcript attached." },
      { mono: "H", name: "Webhooks", description: "Get a request when a conversation starts, hands off or closes." },
    ],
  },
];

export default function IntegrationsPage() {
  return (
    <>
      <PageHeader
        title="Integrations"
        description="Where your assistants can be reached, and where what they learn can go."
      />

      <div className="flex flex-col gap-8">
        {GROUPS.map((group) => (
          <section key={group.title}>
            <h2 className="mb-3 text-[12px] font-semibold uppercase tracking-[0.06em] text-muted">{group.title}</h2>
            <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-4">
              {group.items.map((integration) => (
                <IntegrationCard key={integration.name} integration={integration} />
              ))}
            </div>
          </section>
        ))}
      </div>

      <p className="mt-8 max-w-3xl text-[13px] leading-relaxed text-muted">
        Each unbuilt integration is a real piece of work: an OAuth flow, a message format, and somewhere for
        delivery failures to go. The widget and the REST API cover the case this product is for, so they came first.
      </p>
    </>
  );
}

function IntegrationCard({ integration }: { integration: Integration }) {
  const available = Boolean(integration.href);
  return (
    <article
      className={cx(
        "flex flex-col gap-3.5 rounded-2xl border p-5",
        available ? "border-line bg-surface shadow-soft" : "border-dashed border-[#e2d4c5] bg-cream/60",
      )}
    >
      <div className="flex items-center gap-3">
        <span
          className={cx(
            "grid h-11 w-11 shrink-0 place-items-center rounded-xl text-[16px] font-semibold",
            available ? "bg-accent-soft text-accent" : "bg-[#f3ece4] text-[#5f5249]",
          )}
          aria-hidden
        >
          {integration.mono}
        </span>
        <h3 className="min-w-0 flex-1 text-[16px] font-semibold">{integration.name}</h3>
        {available ? <Badge tone="ok">Available</Badge> : <Badge>Not built yet</Badge>}
      </div>
      <p className="flex-1 text-[14px] leading-normal text-[#4a3d35]">{integration.description}</p>
      {available && (
        <Link
          href={integration.href!}
          className="inline-flex min-h-11 items-center self-start rounded-xl bg-accent px-4 text-sm font-semibold text-white hover:bg-accent-strong"
        >
          Set up
        </Link>
      )}
    </article>
  );
}
