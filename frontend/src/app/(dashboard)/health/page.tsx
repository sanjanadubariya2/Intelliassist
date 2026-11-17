"use client";

import { HeartPulse } from "lucide-react";
import { WithBot } from "@/components/bot-picker";
import { KnowledgeHealthView } from "@/components/knowledge-health";

export default function HealthPage() {
  return (
    <WithBot
      title="Knowledge Base Health"
      description="Find outdated, duplicate or conflicting content before a visitor does, and get FAQs suggested by the questions your bot could not answer."
      eyebrow={
        <>
          <HeartPulse className="h-3.5 w-3.5" /> Health monitor
        </>
      }
    >
      {(bot) => <KnowledgeHealthView key={bot.id} botId={bot.id} />}
    </WithBot>
  );
}
