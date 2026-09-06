"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Bot as BotIcon } from "lucide-react";
import { api, type Bot } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { Card, Empty, ErrorNote, Loading, Select } from "@/components/ui";

/**
 * Wraps a screen that only makes sense for one bot.
 *
 * Customization, API keys and domains are all per-bot, and every one of them
 * otherwise repeats the same four states: loading, failed, no bots at all, and
 * a picker. Having them diverge is how one of them ends up with no empty state.
 */
export function WithBot({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: (bot: Bot, reload: () => void) => React.ReactNode;
}) {
  const { data, loading, error, reload } = useAsync(() => api.bots.list());
  const [botId, setBotId] = useState<string | null>(null);

  // Memoised because the effect below depends on it: `data?.items ?? []`
  // is a fresh array every render, which would re-run the effect forever.
  const bots = useMemo(() => data?.items ?? [], [data]);

  // Select the first bot once they load, and recover if the chosen one is gone.
  useEffect(() => {
    if (!bots.length) return;
    if (!botId || !bots.some((bot) => bot.id === botId)) setBotId(bots[0].id);
  }, [bots, botId]);

  const selected = bots.find((bot) => bot.id === botId) ?? null;

  return (
    <>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
          <p className="mt-0.5 text-[13px] text-muted">{description}</p>
        </div>

        {bots.length > 1 && (
          <Select
            value={botId ?? ""}
            onChange={(event) => setBotId(event.target.value)}
            className="w-auto"
            aria-label="Bot"
          >
            {bots.map((bot) => (
              <option key={bot.id} value={bot.id}>
                {bot.name}
              </option>
            ))}
          </Select>
        )}
      </div>

      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : !selected ? (
        <Card>
          <Empty
            icon={<BotIcon className="h-7 w-7" />}
            title="No bots yet"
            description="Create a bot first — this screen configures one."
            action={
              <Link
                href="/bots"
                className="inline-flex rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent-strong"
              >
                Create a bot
              </Link>
            }
          />
        </Card>
      ) : (
        children(selected, reload)
      )}
    </>
  );
}
