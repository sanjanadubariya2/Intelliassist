"use client";

import { Suspense, use } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { api } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { Badge, ErrorNote, Loading, Tabs } from "@/components/ui";
import { KnowledgeHealthView } from "@/components/knowledge-health";
import { ConversationInsightsView } from "@/components/conversation-insights";
import { WidgetStudio } from "@/components/widget-studio";
import { BotSettings } from "./settings";
import { BotKnowledge } from "./knowledge";
import { BotPlayground } from "./playground";

const TABS = [
  { id: "knowledge", label: "Knowledge" },
  { id: "health", label: "Health" },
  { id: "playground", label: "Playground" },
  { id: "insights", label: "Insights" },
  { id: "widget", label: "Widget" },
  { id: "settings", label: "Settings" },
] as const;
type Tab = (typeof TABS)[number]["id"];

export default function BotDetailPage({ params }: { params: Promise<{ botId: string }> }) {
  // Route params are a promise in Next 15; `use` unwraps it in a client component.
  const { botId } = use(params);
  return (
    <Suspense fallback={<Loading />}>
      <BotDetail botId={botId} />
    </Suspense>
  );
}

function BotDetail({ botId }: { botId: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();

  // The tab is in the URL, so a link can open a bot on its Health tab, and
  // reloading the page does not throw the user back to the first tab.
  const tab = (TABS.find((entry) => entry.id === params.get("tab"))?.id ?? "knowledge") as Tab;
  const setTab = (next: Tab) => router.replace(`${pathname}?tab=${next}`, { scroll: false });

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
        <h1 className="font-display text-[28px] font-semibold tracking-tight">{bot.name}</h1>
        <Badge tone={bot.status === "ACTIVE" ? "ok" : "warn"}>
          {bot.status === "ACTIVE" ? "Online" : "Paused"}
        </Badge>
      </div>

      <Tabs tabs={TABS} value={tab} onChange={setTab} />

      {tab === "knowledge" && <BotKnowledge botId={botId} />}
      {tab === "health" && <KnowledgeHealthView botId={botId} />}
      {tab === "playground" && <BotPlayground botId={botId} botName={bot.name} />}
      {tab === "insights" && <ConversationInsightsView botId={botId} />}
      {tab === "widget" && <WidgetStudio bot={bot} onSaved={reload} />}
      {tab === "settings" && <BotSettings bot={bot} onSaved={reload} />}
    </>
  );
}
