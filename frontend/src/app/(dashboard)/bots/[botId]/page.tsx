"use client";

import { use, useState } from "react";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { api } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { Badge, ErrorNote, Loading, cx } from "@/components/ui";
import { BotSettings } from "./settings";
import { BotKnowledge } from "./knowledge";
import { BotEmbed } from "./embed";
import { BotPlayground } from "./playground";

const TABS = ["Knowledge", "Playground", "Embed", "Settings"] as const;
type Tab = (typeof TABS)[number];

export default function BotDetailPage({ params }: { params: Promise<{ botId: string }> }) {
  // Route params are a promise in Next 15; `use` unwraps it in a client component.
  const { botId } = use(params);
  const [tab, setTab] = useState<Tab>("Knowledge");

  const { data: bot, loading, error, reload } = useAsync(() => api.bots.get(botId), [botId]);

  if (loading) return <Loading />;
  if (error) return <ErrorNote message={error} onRetry={reload} />;
  if (!bot) return null;

  return (
    <>
      <Link
        href="/bots"
        className="mb-4 inline-flex items-center gap-1.5 text-[13px] text-muted hover:text-ink"
      >
        <ArrowLeft className="h-3.5 w-3.5" />
        All bots
      </Link>

      <div className="mb-5 flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold tracking-tight">{bot.name}</h1>
        <Badge tone={bot.status === "ACTIVE" ? "ok" : "warn"}>
          {bot.status === "ACTIVE" ? "Online" : "Paused"}
        </Badge>
      </div>

      <div className="mb-5 flex gap-1 overflow-x-auto border-b border-line">
        {TABS.map((name) => (
          <button
            key={name}
            onClick={() => setTab(name)}
            className={cx(
              "-mb-px whitespace-nowrap border-b-2 px-3.5 py-2.5 text-sm transition-colors",
              tab === name
                ? "border-accent font-medium text-accent"
                : "border-transparent text-muted hover:text-ink",
            )}
          >
            {name}
          </button>
        ))}
      </div>

      {tab === "Knowledge" && <BotKnowledge botId={botId} />}
      {tab === "Playground" && <BotPlayground botId={botId} />}
      {tab === "Embed" && <BotEmbed botId={botId} />}
      {tab === "Settings" && <BotSettings bot={bot} onSaved={reload} />}
    </>
  );
}
