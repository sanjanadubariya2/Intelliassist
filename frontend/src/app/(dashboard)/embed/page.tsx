"use client";

import { WithBot } from "@/components/bot-picker";
import { BotEmbed } from "../bots/[botId]/embed";

/**
 * API keys, allowed domains and the install snippet, at the top level.
 *
 * The same component the bot's own Embed tab renders. Two copies would drift,
 * and the one nobody looks at would be the one that breaks.
 */
export default function EmbedPage() {
  return (
    <WithBot
      title="API & Embed"
      description="Install the widget on your site and manage its credentials."
    >
      {(bot) => <BotEmbed key={bot.id} botId={bot.id} />}
    </WithBot>
  );
}
