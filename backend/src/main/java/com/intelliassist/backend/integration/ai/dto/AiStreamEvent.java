package com.intelliassist.backend.integration.ai.dto;

/**
 * One Server-Sent Event from the AI service, still unparsed.
 *
 * <p>The payload stays a JSON string on purpose. Most events are tokens that
 * are forwarded verbatim, and deserialising every one into a typed object only
 * to serialise it straight back would cost work per token for no benefit. The
 * two events we actually need to understand - {@code citations} and
 * {@code done} - are parsed on arrival, and there are only ever one of each.
 *
 * @param name event name: metadata, token, citations, done or error
 * @param data raw JSON payload
 */
public record AiStreamEvent(String name, String data) {

    public static final String METADATA = "metadata";
    public static final String TOKEN = "token";
    public static final String CITATIONS = "citations";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    public boolean is(String eventName) {
        return eventName.equals(name);
    }

    /** True for the event that means the answer is complete and persistable. */
    public boolean isTerminal() {
        return is(DONE);
    }
}
