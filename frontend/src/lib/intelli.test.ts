/**
 * Ask Intelli's parser. Run with `npm test` (Node's built-in runner, no extra
 * dependencies).
 *
 * The cases that matter most are the ones where a misread would change a live
 * bot: "enable dark mode" must not resume a paused bot, and a change request
 * must always arrive as a proposal rather than an action.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { interpret, setupSteps, type SetupState } from "./intelli.ts";
import type { Bot } from "./api";

const bot: Bot = {
  id: "b1",
  organizationId: "o1",
  name: "Support",
  description: null,
  status: "ACTIVE",
  defaultLanguage: "en",
  systemPrompt: null,
  primaryColor: "#7C5AED",
  widgetPosition: "BOTTOM_RIGHT",
  welcomeMessage: "Hi there! 👋 How can I help you today?",
  fallbackMessage: "Sorry, I don't know.",
  launcherIcon: "CHAT",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const state: SetupState = {
  bot,
  bots: [bot],
  documents: 0,
  indexedDocuments: 0,
  activeKeys: 0,
  domains: 0,
};

test("a named colour becomes an appearance proposal, not a change", () => {
  const reply = interpret("Change the widget colour to forest green", state);
  assert.equal(reply.proposal?.action.kind, "appearance");
  assert.deepEqual(reply.proposal?.action, {
    kind: "appearance",
    botId: "b1",
    patch: { primaryColor: "#1F4B3B" },
  });
});

test("a hex colour is taken as given", () => {
  const reply = interpret("make the brand color #ff5722", state);
  assert.equal(reply.proposal?.changes[0].to, "#FF5722");
});

test("quoted messages keep their apostrophes", () => {
  const reply = interpret('Set the welcome message to "We\'re here to help!"', state);
  assert.equal(reply.proposal?.changes[0].to, "We're here to help!");
});

test("the fallback message is distinguished from the welcome message", () => {
  const reply = interpret('change the fallback message to "Email us at help@acme.com"', state);
  assert.equal(reply.proposal?.changes[0].label, "Fallback message");
});

test("position requests are understood", () => {
  const reply = interpret("move the widget to the bottom left", state);
  assert.deepEqual(reply.proposal?.action, {
    kind: "appearance",
    botId: "b1",
    patch: { widgetPosition: "BOTTOM_LEFT" },
  });
});

test("pausing proposes a status change", () => {
  const reply = interpret("Pause the bot", state);
  assert.deepEqual(reply.proposal?.action, { kind: "status", botId: "b1", status: "INACTIVE" });
});

test("'enable dark mode' is not read as resuming the bot", () => {
  const paused = { ...state, bot: { ...bot, status: "INACTIVE" as const }, bots: [{ ...bot, status: "INACTIVE" as const }] };
  const reply = interpret("Can I enable dark mode?", paused);
  assert.equal(reply.proposal, undefined);
});

test("creating a bot captures its name", () => {
  const reply = interpret("create a bot called Billing Helper", state);
  assert.deepEqual(reply.proposal?.action, { kind: "createBot", name: "Billing Helper" });
});

test("a how-to question about creating a bot is not a creation", () => {
  const reply = interpret("How do I create a bot?", state);
  assert.equal(reply.proposal, undefined);
});

test("allowing a domain proposes it", () => {
  const reply = interpret("allow shop.acme.com", state);
  assert.deepEqual(reply.proposal?.action, { kind: "addDomain", botId: "b1", domain: "shop.acme.com" });
});

test("language codes are normalised", () => {
  const reply = interpret("set the language to en-gb", state);
  assert.deepEqual(reply.proposal?.action, { kind: "language", botId: "b1", language: "en-GB" });
});

test("how-to questions get explanations with links", () => {
  const reply = interpret("How do I add the chatbot to my website?", state);
  assert.equal(reply.proposal, undefined);
  assert.ok(reply.links?.some((link) => link.href.startsWith("/studio")));
});

test("'what next' points at the first unfinished step", () => {
  const reply = interpret("What should I do next?", state);
  assert.match(reply.text, /Upload a document/);
});

test("with no bots, change requests ask for one first", () => {
  const reply = interpret("change the colour to blue", { ...state, bot: null, bots: [] });
  assert.equal(reply.proposal, undefined);
  assert.match(reply.text, /don't have a bot/);
});

test("setup steps tick off as the workspace fills in", () => {
  const steps = setupSteps({ ...state, documents: 2, indexedDocuments: 1, activeKeys: 1, domains: 1 });
  const done = Object.fromEntries(steps.map((step) => [step.id, step.done]));
  assert.equal(done.bot, true);
  assert.equal(done.indexed, true);
  assert.equal(done.domain, true);
  assert.equal(done.style, false);
});
