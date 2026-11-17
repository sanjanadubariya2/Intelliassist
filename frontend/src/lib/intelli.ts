/**
 * Ask Intelli: the setup guide's brain.
 *
 * Deliberately deterministic. It reads plain-language requests with patterns,
 * explains features from a fixed, accurate script, and turns change requests
 * into a *proposal*: nothing is written until a person approves it on screen.
 * A language model would phrase things more loosely, but it could also invent
 * a feature or misread "pause" as "delete"; for a tool that changes a live
 * customer-facing bot, predictable beats clever.
 *
 * Everything here is pure, so it is tested without a browser or a backend.
 */

import type { Bot, BotStatus, LauncherIcon, WidgetPosition } from "./api";

// ------------------------------------------------------------------ types --

export interface SetupState {
  bot: Bot | null;
  bots: Bot[];
  documents: number;
  indexedDocuments: number;
  activeKeys: number;
  domains: number;
}

export type Action =
  | { kind: "appearance"; botId: string; patch: AppearancePatch }
  | { kind: "status"; botId: string; status: BotStatus }
  | { kind: "rename"; botId: string; name: string }
  | { kind: "language"; botId: string; language: string }
  | { kind: "createBot"; name: string }
  | { kind: "addDomain"; botId: string; domain: string };

export interface AppearancePatch {
  primaryColor?: string;
  widgetPosition?: WidgetPosition;
  welcomeMessage?: string;
  fallbackMessage?: string;
  launcherIcon?: LauncherIcon;
}

export interface Change {
  label: string;
  from: string;
  to: string;
  /** Hex colours render as swatches. */
  colour?: boolean;
}

export interface Proposal {
  title: string;
  summary: string;
  changes: Change[];
  action: Action;
}

export interface Link {
  label: string;
  href: string;
}

export interface Reply {
  text: string;
  bullets?: string[];
  links?: Link[];
  proposal?: Proposal;
  suggestions?: string[];
}

// -------------------------------------------------------------- vocabulary --

export const COLOURS: Record<string, string> = {
  forest: "#1F4B3B",
  green: "#15803D",
  terracotta: "#C0583A",
  clay: "#C0583A",
  orange: "#EA580C",
  red: "#DC2626",
  pink: "#DB2777",
  purple: "#7C3AED",
  violet: "#7C5AED",
  indigo: "#4F46E5",
  blue: "#2563EB",
  navy: "#1E3A8A",
  teal: "#0F766E",
  black: "#111827",
  grey: "#4B5563",
  gray: "#4B5563",
  brown: "#92400E",
  gold: "#D97706",
  yellow: "#CA8A04",
};

const ICON_WORDS: Record<string, LauncherIcon> = {
  chat: "CHAT",
  bubble: "CHAT",
  message: "MESSAGE",
  speech: "MESSAGE",
  help: "HELP",
  question: "HELP",
  sparkle: "SPARKLE",
  sparkles: "SPARKLE",
  star: "SPARKLE",
  magic: "SPARKLE",
};

const POSITION_LABEL: Record<WidgetPosition, string> = {
  BOTTOM_RIGHT: "Bottom right",
  BOTTOM_LEFT: "Bottom left",
  TOP_RIGHT: "Top right",
  TOP_LEFT: "Top left",
};

export const STARTERS = [
  "What should I do next?",
  "How does IntelliAssist answer questions?",
  "Change the widget colour to forest green",
  "Set the welcome message to \"Hi! How can we help today?\"",
  "How do I add the chatbot to my website?",
  "What is the handoff inbox?",
];

// ------------------------------------------------------------------ setup --

export interface Step {
  id: string;
  label: string;
  done: boolean;
  href: string;
  hint: string;
}

/** The setup checklist, in the order a bot actually becomes useful. */
export function setupSteps(state: SetupState): Step[] {
  const bot = state.bot;
  const botQuery = bot ? `?bot=${bot.id}` : "";
  return [
    {
      id: "bot",
      label: "Create a bot",
      done: state.bots.length > 0,
      href: "/bots",
      hint: "A bot is one assistant with its own documents and widget.",
    },
    {
      id: "docs",
      label: "Upload a document",
      done: state.documents > 0,
      href: bot ? `/bots/${bot.id}` : "/bots",
      hint: "PDF, DOCX, TXT or Markdown. The bot answers only from these.",
    },
    {
      id: "indexed",
      label: "Wait for it to be ready",
      done: state.indexedDocuments > 0,
      href: bot ? `/bots/${bot.id}` : "/bots",
      hint: "Indexing runs in the background and usually takes a minute.",
    },
    {
      id: "test",
      label: "Test it in the playground",
      // Not trackable from here without reading conversations, so it is
      // offered once there is something to test rather than ticked.
      done: false,
      href: `/playground${botQuery}`,
      hint: "Ask what your customers would, and check the sources it cites.",
    },
    {
      id: "style",
      label: "Style the widget",
      done: bot ? bot.welcomeMessage !== DEFAULT_WELCOME || bot.primaryColor.toUpperCase() !== DEFAULT_COLOUR : false,
      href: `/studio${botQuery}`,
      hint: "Your colour, your greeting, your position on the page.",
    },
    {
      id: "key",
      label: "Create an API key",
      done: state.activeKeys > 0,
      href: `/studio${botQuery ? `${botQuery}&` : "?"}tab=install`,
      hint: "The widget authenticates with it. Shown once, so copy it.",
    },
    {
      id: "domain",
      label: "Allow your website's domain",
      done: state.domains > 0,
      href: `/studio${botQuery ? `${botQuery}&` : "?"}tab=install`,
      hint: "Without one, the key answers nobody. That is the safety catch.",
    },
  ];
}

const DEFAULT_WELCOME = "Hi there! 👋 How can I help you today?";
const DEFAULT_COLOUR = "#7C5AED";

// ---------------------------------------------------------------- explain --

interface Topic {
  match: RegExp;
  reply: (state: SetupState) => Reply;
}

const TOPICS: Topic[] = [
  {
    match: /\b(rag|ground(ed|ing)?|citation|cite|source|how (does|do) (it|you|intelli|intelliassist) (answer|work)|hallucinat)/,
    reply: () => ({
      text: "IntelliAssist answers with retrieval-augmented generation (RAG). It never answers from general knowledge.",
      bullets: [
        "Your question is matched against your documents with hybrid search: meaning-based vectors plus keyword (BM25) search, fused and re-ranked.",
        "Only the best passages are given to the model, which must answer from them and cite them.",
        "If nothing relevant is found, it declines with your fallback message instead of guessing. Those are the \"ungrounded\" answers in Insights.",
      ],
      links: [
        { label: "Try it in the playground", href: "/playground" },
        { label: "See unanswered questions", href: "/insights" },
      ],
    }),
  },
  {
    match: /\b(upload|document|knowledge( base)?|file|pdf|docx|train)/,
    reply: (state) => ({
      text: "Your bot learns from the documents in its knowledge base.",
      bullets: [
        "Upload PDF, DOCX, TXT or Markdown from a bot's Knowledge tab.",
        "Each file is read, split into passages and indexed in the background. It shows Ready when it can be used.",
        "Keep one current version of each document. KB Health flags duplicates and old versions.",
      ],
      links: [
        { label: "Open Knowledge Base", href: state.bot ? `/bots/${state.bot.id}` : "/knowledge" },
        { label: "Check KB Health", href: "/health" },
      ],
    }),
  },
  {
    match: /\b(embed|install|website|script|snippet|widget code|put (it|the (bot|chatbot|widget)) on)/,
    reply: () => ({
      text: "Adding the chatbot to your site takes three steps, all in Widget Studio's Install tab:",
      bullets: [
        "Create an API key and copy it. It is shown only once.",
        "Allow your site's domain, e.g. acme.com. The key is refused anywhere else.",
        "Paste the script tag before </body> on your pages.",
      ],
      links: [{ label: "Open Widget Studio → Install", href: "/studio?tab=install" }],
    }),
  },
  {
    match: /\b(api key|key|domain|allow(ed)? (site|domain)|cors)/,
    reply: () => ({
      text: "Widget keys are public by design, because they sit in your page source. Allowed domains are what make that safe: a key only works on the domains you list.",
      bullets: [
        "Keys are stored only as a hash, so a lost key cannot be recovered. Create a new one and revoke the old.",
        "Say \"allow acme.com\" and I'll propose adding it.",
      ],
      links: [{ label: "Manage keys and domains", href: "/studio?tab=install" }],
    }),
  },
  {
    match: /\b(handoff|hand off|escalat|human|agent|inbox|assign)/,
    reply: () => ({
      text: "The Handoff Inbox is where conversations that need a person end up.",
      bullets: [
        "Escalate a conversation, or assign it to a teammate, and it lands in the shared queue.",
        "Take it, add internal notes, and mark it resolved. Visitors never see notes.",
        "The inbox tracks the median time from escalation to resolution.",
      ],
      links: [{ label: "Open the inbox", href: "/inbox" }],
    }),
  },
  {
    match: /\b(health|outdated|duplicate|conflict|stale|old version)/,
    reply: () => ({
      text: "KB Health scores a knowledge base out of 100 and lists what to fix:",
      bullets: [
        "Failed or stuck uploads, and documents with no readable text.",
        "Several live versions of the same document, which can contradict each other.",
        "Duplicates, and documents whose name points to a past year.",
        "Suggested FAQs: real questions from the last 30 days that no document answered.",
      ],
      links: [{ label: "Run a health check", href: "/health" }],
    }),
  },
  {
    match: /\b(insight|analytic|satisfaction|common question|unanswered|trend|metric|report)/,
    reply: () => ({
      text: "Conversation Intelligence shows what visitors ask and how conversations end.",
      bullets: [
        "Common questions, grouped when only case or punctuation differs.",
        "Unanswered queries: the clearest list of what to add to your documents.",
        "Satisfaction is resolved ÷ (resolved + escalated). There is no star rating yet, so it is a proxy, and labelled as one.",
      ],
      links: [
        { label: "Open Insights", href: "/insights" },
        { label: "Usage analytics", href: "/analytics" },
      ],
    }),
  },
  {
    match: /\b(playground|test)/,
    reply: () => ({
      text: "The Chat Playground is your bot exactly as a visitor sees it, with each answer's sources shown underneath.",
      bullets: ["Ask one question your documents cover and one they don't. It should decline the second rather than invent."],
      links: [{ label: "Open the playground", href: "/playground" }],
    }),
  },
  {
    match: /\b(system prompt|persona|tone of voice|instructions?)/,
    reply: () => ({
      text: "The system prompt is saved with the bot, but it does not change answers yet: the AI service's query has no field to carry it.",
      bullets: ["Your welcome and fallback messages do reach visitors. Change those for tone today."],
      links: [{ label: "Edit messages in Widget Studio", href: "/studio?tab=messages" }],
    }),
  },
  {
    match: /\b(price|pricing|plan|bill|cost|token)/,
    reply: () => ({
      text: "Billing shows this month's usage: conversations, messages and tokens per bot. There is no payment provider connected yet, so there is no plan or invoice.",
      links: [{ label: "Open Billing", href: "/billing" }],
    }),
  },
  {
    match: /\b(welcome|fallback|greeting)\b/,
    reply: () => ({
      text: "The welcome message is the first line every visitor reads. The fallback is what the bot says when nothing in your documents matches.",
      bullets: ["Say: set the welcome message to \"Hi! Ask me anything about your order.\""],
      links: [{ label: "Edit in Widget Studio", href: "/studio?tab=messages" }],
    }),
  },
];

// -------------------------------------------------------------- interpret --

/** Text inside quotes, or everything after "to". */
function payloadAfterTo(text: string): string | null {
  // Double quotes first, so an apostrophe inside ("We're here") survives.
  const quoted =
    text.match(/"([^"]{1,500})"/) ??
    text.match(/“([^”]{1,500})”/) ??
    text.match(/(?:^|\s)'([^']{1,500})'(?:\s|[.!?]?$)/);
  if (quoted) return quoted[1].trim();
  const after = text.match(/\bto\s*[:\-]?\s+(.{1,500})$/i);
  return after ? after[1].trim().replace(/[.]$/, "") : null;
}

function findBot(text: string, state: SetupState): Bot | null {
  const lower = text.toLowerCase();
  // Longest name first, so "Support Bot EU" wins over "Support Bot".
  const named = [...state.bots]
    .sort((a, b) => b.name.length - a.name.length)
    .find((bot) => lower.includes(bot.name.toLowerCase()));
  return named ?? state.bot;
}

function needBot(state: SetupState): Reply {
  return {
    text: state.bots.length === 0
      ? "You don't have a bot yet. Say \"create a bot called Support\" and I'll set one up for your approval."
      : "Which bot? Pick one above, or mention it by name.",
    suggestions: state.bots.length === 0 ? ["Create a bot called Support"] : [],
  };
}

function appearance(bot: Bot, patch: AppearancePatch, title: string, summary: string): Reply {
  const changes: Change[] = [];
  if (patch.primaryColor) changes.push({ label: "Brand colour", from: bot.primaryColor, to: patch.primaryColor, colour: true });
  if (patch.widgetPosition) changes.push({ label: "Position", from: POSITION_LABEL[bot.widgetPosition], to: POSITION_LABEL[patch.widgetPosition] });
  if (patch.launcherIcon) changes.push({ label: "Launcher icon", from: bot.launcherIcon.toLowerCase(), to: patch.launcherIcon.toLowerCase() });
  if (patch.welcomeMessage !== undefined) changes.push({ label: "Welcome message", from: bot.welcomeMessage, to: patch.welcomeMessage });
  if (patch.fallbackMessage !== undefined) changes.push({ label: "Fallback message", from: bot.fallbackMessage, to: patch.fallbackMessage });

  return {
    text: "Here's what I'd change. Nothing is saved until you approve it.",
    proposal: { title, summary, changes, action: { kind: "appearance", botId: bot.id, patch } },
  };
}

/**
 * Reads one message and decides what to say.
 *
 * Change requests are checked first, because "change the welcome message" also
 * contains the word "welcome" and must not be answered with an explanation.
 */
export function interpret(input: string, state: SetupState): Reply {
  const text = input.trim();
  const lower = text.toLowerCase();

  if (!text) return { text: "Ask me anything about setting up your chatbot." };

  // ---- create a bot
  const create = text.match(/\b(?:create|make|add|set up|new)\s+(?:a\s+|an\s+)?(?:new\s+)?(?:bot|chatbot|assistant)(?:\s+(?:called|named))?\s*["“']?([^"”']{2,120}?)["”']?\s*$/i);
  if (create && create[1] && !/^(for|to|that|which)\b/i.test(create[1])) {
    const name = create[1].trim();
    return {
      text: "I'll create the bot. You can add documents to it straight after.",
      proposal: {
        title: "Create a bot",
        summary: `A new assistant named "${name}".`,
        changes: [{ label: "Name", from: "—", to: name }],
        action: { kind: "createBot", name },
      },
    };
  }

  const bot = findBot(text, state);
  const isChange = /\b(change|set|make|update|use|switch|turn|move|put|rename|pause|resume|activate|deactivate|enable|disable|allow|whitelist|add)\b/.test(lower);

  if (isChange) {
    // ---- welcome / fallback message
    if (/\bwelcome\b|\bgreeting\b/.test(lower)) {
      if (!bot) return needBot(state);
      const message = payloadAfterTo(text);
      if (!message) return { text: "What should it say? For example: set the welcome message to \"Hi! How can we help?\"" };
      if (message.length > 500) return { text: "Welcome messages can be at most 500 characters." };
      return appearance(bot, { welcomeMessage: message }, "Update the welcome message", `For ${bot.name}.`);
    }
    if (/\bfallback\b/.test(lower)) {
      if (!bot) return needBot(state);
      const message = payloadAfterTo(text);
      if (!message) return { text: "What should it say when it can't answer? For example: set the fallback message to \"I'm not sure. Email help@acme.com.\"" };
      if (message.length > 500) return { text: "Fallback messages can be at most 500 characters." };
      return appearance(bot, { fallbackMessage: message }, "Update the fallback message", `For ${bot.name}.`);
    }

    // ---- colour
    if (/\b(colou?r|theme|brand)\b/.test(lower)) {
      if (!bot) return needBot(state);
      const hex = text.match(/#(?:[0-9a-f]{6}|[0-9a-f]{3})\b/i)?.[0];
      const named = Object.keys(COLOURS)
        .sort((a, b) => b.length - a.length)
        .find((name) => new RegExp(`\\b${name}\\b`).test(lower));
      const colour = hex ? hex.toUpperCase() : named ? COLOURS[named] : null;
      if (!colour) {
        return {
          text: "Which colour? Give me a hex value like #1F4B3B, or a name.",
          bullets: [`Names I know: ${Object.keys(COLOURS).join(", ")}.`],
        };
      }
      return appearance(bot, { primaryColor: colour }, "Change the brand colour", `The launcher, header and visitor messages for ${bot.name}.`);
    }

    // ---- position
    const position = lower.match(/\b(bottom|top)[\s-]+(left|right)\b/);
    if (position && /\b(move|put|position|place|corner|side|change|set)\b/.test(lower)) {
      if (!bot) return needBot(state);
      const value = `${position[1].toUpperCase()}_${position[2].toUpperCase()}` as WidgetPosition;
      return appearance(bot, { widgetPosition: value }, "Move the widget", `Where ${bot.name} sits on your pages.`);
    }

    // ---- launcher icon
    if (/\bicon\b|\blauncher\b/.test(lower)) {
      if (!bot) return needBot(state);
      const word = Object.keys(ICON_WORDS).find((key) => new RegExp(`\\b${key}\\b`).test(lower));
      if (!word) return { text: "Which icon? I can use chat, message, help or sparkle." };
      return appearance(bot, { launcherIcon: ICON_WORDS[word] }, "Change the launcher icon", `For ${bot.name}.`);
    }

    // ---- status. Only when the sentence is about the bot: "enable dark
    // mode" must not be read as "resume the bot".
    const aboutBot =
      /\b(bot|chatbot|assistant|widget|it)\b/.test(lower) ||
      state.bots.some((candidate) => lower.includes(candidate.name.toLowerCase()));
    if (aboutBot && /\b(pause|deactivate|disable|turn off|take (it )?offline|switch off|stop)\b/.test(lower)) {
      if (!bot) return needBot(state);
      return {
        text: bot.status === "INACTIVE" ? `${bot.name} is already paused.` : "Pausing stops the bot answering on your site straight away. Conversations and documents are kept.",
        proposal: bot.status === "INACTIVE" ? undefined : {
          title: `Pause ${bot.name}`,
          summary: "The widget stops answering until you resume it.",
          changes: [{ label: "Status", from: "Online", to: "Paused" }],
          action: { kind: "status", botId: bot.id, status: "INACTIVE" },
        },
      };
    }
    if (aboutBot && /\b(resume|activate|enable|turn on|go live|bring (it )?(back )?online|switch on|unpause)\b/.test(lower)) {
      if (!bot) return needBot(state);
      return {
        text: bot.status === "ACTIVE" ? `${bot.name} is already online.` : "Resuming lets the widget answer again.",
        proposal: bot.status === "ACTIVE" ? undefined : {
          title: `Resume ${bot.name}`,
          summary: "The widget starts answering again.",
          changes: [{ label: "Status", from: "Paused", to: "Online" }],
          action: { kind: "status", botId: bot.id, status: "ACTIVE" },
        },
      };
    }

    // ---- rename
    if (/\brename\b|\bname\b/.test(lower)) {
      if (!bot) return needBot(state);
      const name = payloadAfterTo(text);
      if (!name) return { text: "What should it be called? For example: rename the bot to \"Acme Help\"." };
      if (name.length > 120) return { text: "Bot names can be at most 120 characters." };
      return {
        text: "Here's the rename. Visitors see the new name in the widget header.",
        proposal: {
          title: "Rename the bot",
          summary: `Currently "${bot.name}".`,
          changes: [{ label: "Name", from: bot.name, to: name }],
          action: { kind: "rename", botId: bot.id, name },
        },
      };
    }

    // ---- language
    const language = lower.match(/\blanguage\b.*?\b([a-z]{2}(?:-[a-z]{2})?)\s*$/);
    if (/\blanguage\b/.test(lower)) {
      if (!bot) return needBot(state);
      if (!language) return { text: "Which language code? For example: set the language to fr, or en-GB." };
      const [base, region] = language[1].split("-");
      const code = region ? `${base}-${region.toUpperCase()}` : base;
      return {
        text: "Here's the change.",
        proposal: {
          title: "Change the default language",
          summary: `For ${bot.name}.`,
          changes: [{ label: "Language", from: bot.defaultLanguage, to: code }],
          action: { kind: "language", botId: bot.id, language: code },
        },
      };
    }

    // ---- domain
    const domain = lower.match(/\b((?:[a-z0-9-]+\.)+[a-z]{2,})\b/);
    if (domain && /\b(allow|whitelist|add|domain)\b/.test(lower)) {
      if (!bot) return needBot(state);
      return {
        text: "Allowing a domain lets the widget key work on that site, and every page on it.",
        proposal: {
          title: "Allow a domain",
          summary: `For ${bot.name}'s widget.`,
          changes: [{ label: "Allowed domain", from: "—", to: domain[1] }],
          action: { kind: "addDomain", botId: bot.id, domain: domain[1] },
        },
      };
    }
  }

  // ---- what next
  if (/\b(next|start|get(ting)? started|set ?up|checklist|progress|onboard|ready|launch|what (should|do) i)\b/.test(lower)) {
    const steps = setupSteps(state);
    const todo = steps.filter((step) => !step.done && step.id !== "test");
    if (todo.length === 0) {
      return {
        text: `${state.bot?.name ?? "Your bot"} is fully set up. 🎉`,
        bullets: [
          "Keep an eye on the Handoff Inbox for conversations that need a person.",
          "Check Insights weekly for unanswered questions, and turn them into FAQs.",
        ],
        links: [
          { label: "Open Insights", href: "/insights" },
          { label: "Run KB Health", href: "/health" },
        ],
      };
    }
    const next = todo[0];
    return {
      text: `Next step: **${next.label}**. ${next.hint}`,
      bullets: todo.slice(1, 4).map((step) => `Then: ${step.label.toLowerCase()}`),
      links: [{ label: next.label, href: next.href }],
    };
  }

  // ---- explain
  for (const topic of TOPICS) {
    if (topic.match.test(lower)) return topic.reply(state);
  }

  // ---- hello / help
  if (/^(hi|hey|hello|yo|hiya|good (morning|afternoon|evening))\b|\bhelp\b|what can you do/.test(lower)) {
    return {
      text: "Hi, I'm Intelli, your setup guide. I can:",
      bullets: [
        "Tell you what to do next to get your chatbot live.",
        "Explain any feature: RAG, KB Health, Widget Studio, the Handoff Inbox, Insights.",
        "Make changes for you, like colour, messages, position, status, name or allowed domains. You approve each one first.",
      ],
      suggestions: STARTERS.slice(0, 4),
    };
  }

  return {
    text: "I didn't catch that. I'm best at setup questions and simple changes.",
    suggestions: [
      "What should I do next?",
      "Change the colour to blue",
      "Move the widget to bottom left",
      "Pause the bot",
    ],
  };
}
