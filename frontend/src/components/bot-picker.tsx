"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Bot as BotIcon } from "lucide-react";
import { api, type Bot } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { Card, Empty, ErrorNote, Loading, PageHeader, Select, buttonLink } from "@/components/ui";

/**
 * Wraps a screen that only makes sense for one bot.
 *
 * Customization, health, insights and the playground are all per bot, and every
 * one of them otherwise repeats the same four states: loading, failed, no bots
 * at all, and a picker. Having them diverge is how one of them ends up with no
 * empty state.
 *
 * The choice is kept in the URL (`?bot=`), so a link to "insights for the
 * Support bot" works, and moving between these screens keeps the same bot.
 */
export function WithBot(props: {
  title: string;
  description: string;
  eyebrow?: React.ReactNode;
  children: (bot: Bot, reload: () => void) => React.ReactNode;
}) {
  // useSearchParams needs a Suspense boundary during prerender.
  return (
    <Suspense fallback={<Loading />}>
      <Picker {...props} />
    </Suspense>
  );
}

function Picker({
  title,
  description,
  eyebrow,
  children,
}: {
  title: string;
  description: string;
  eyebrow?: React.ReactNode;
  children: (bot: Bot, reload: () => void) => React.ReactNode;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { data, loading, error, reload } = useAsync(() => api.bots.list(0, 100));

  // Memoised because the effect below depends on it: `data?.items ?? []`
  // is a fresh array every render, which would re-run the effect forever.
  const bots = useMemo(() => data?.items ?? [], [data]);
  const requested = params.get("bot");
  const [botId, setBotId] = useState<string | null>(requested);

  // Select the requested bot, else the first, and recover if it is gone.
  useEffect(() => {
    if (!bots.length) return;
    if (!botId || !bots.some((bot) => bot.id === botId)) setBotId(bots[0].id);
  }, [bots, botId]);

  function choose(id: string) {
    setBotId(id);
    const next = new URLSearchParams(params.toString());
    next.set("bot", id);
    router.replace(`${pathname}?${next}`, { scroll: false });
  }

  const selected = bots.find((bot) => bot.id === botId) ?? null;

  return (
    <>
      <PageHeader
        title={title}
        description={description}
        eyebrow={eyebrow}
        action={
          bots.length > 1 ? (
            <Select
              value={botId ?? ""}
              onChange={(event) => choose(event.target.value)}
              className="w-auto min-w-48"
              aria-label="Bot"
            >
              {bots.map((bot) => (
                <option key={bot.id} value={bot.id}>
                  {bot.name}
                </option>
              ))}
            </Select>
          ) : undefined
        }
      />

      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : !selected ? (
        <Card>
          <Empty
            icon={<BotIcon className="h-6 w-6" />}
            title="No bots yet"
            description="Create a bot first. This screen works on one."
            action={
              <Link href="/bots" className={buttonLink}>
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
