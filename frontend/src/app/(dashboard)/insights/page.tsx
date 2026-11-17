"use client";

import { Lightbulb } from "lucide-react";
import { WithBot } from "@/components/bot-picker";
import { ConversationInsightsView } from "@/components/conversation-insights";

export default function InsightsPage() {
  return (
    <WithBot
      title="Conversation Intelligence"
      description="Analyze common questions, unanswered queries, satisfaction and resolution trends."
      eyebrow={
        <>
          <Lightbulb className="h-3.5 w-3.5" /> Insights
        </>
      }
    >
      {(bot) => <ConversationInsightsView key={bot.id} botId={bot.id} />}
    </WithBot>
  );
}
