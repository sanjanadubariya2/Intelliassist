"use client";

import { WithBot } from "@/components/bot-picker";
import { BotPlayground } from "../bots/[botId]/playground";

/** The playground at the top level, for whichever bot is picked. */
export default function PlaygroundPage() {
  return (
    <WithBot
      title="Chat Playground"
      description="Ask what your visitors would. Answers stream exactly as they do in the widget, with their sources."
    >
      {(bot) => <BotPlayground key={bot.id} botId={bot.id} botName={bot.name} />}
    </WithBot>
  );
}
