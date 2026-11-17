"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { SlidersHorizontal } from "lucide-react";
import { WithBot } from "@/components/bot-picker";
import { Loading } from "@/components/ui";
import { WidgetStudio, type StudioTab } from "@/components/widget-studio";

export default function StudioPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Studio />
    </Suspense>
  );
}

function Studio() {
  const params = useSearchParams();
  const requested = params.get("tab");
  const tab: StudioTab =
    requested === "install" || requested === "messages" ? requested : "appearance";

  return (
    <WithBot
      title="Widget Studio"
      description="Customize your chatbot, preview it live and get an embed code for your website."
      eyebrow={
        <>
          <SlidersHorizontal className="h-3.5 w-3.5" /> Studio
        </>
      }
    >
      {(bot, reload) => (
        <WidgetStudio key={bot.id} bot={bot} onSaved={reload} initialTab={tab} />
      )}
    </WithBot>
  );
}
