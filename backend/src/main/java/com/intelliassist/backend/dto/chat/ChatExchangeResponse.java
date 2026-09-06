package com.intelliassist.backend.dto.chat;

/**
 * What a client gets back from sending a message: both turns.
 *
 * <p>Returning the persisted user message as well as the answer means the
 * client does not have to guess at ids or timestamps to render the exchange,
 * and a widget can reconcile its optimistic local echo with what was stored.
 */
public record ChatExchangeResponse(MessageResponse userMessage, MessageResponse assistantMessage) {}
