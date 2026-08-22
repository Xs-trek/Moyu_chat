package com.moyu.chat;

/** Protocol registry kept intentionally tiny; providers are data, transports are protocol implementations. */
final class ChatTransportRegistry implements AutoCloseable {
    private final OpenAiCompatibleTransport openAi = new OpenAiCompatibleTransport();
    private final AnthropicMessagesTransport anthropic = new AnthropicMessagesTransport();

    ChatTransport forProtocol(String protocol) {
        if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol) || OpenAiCompatibleTransport.RESPONSES.equals(protocol)) return openAi;
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return anthropic;
        return null;
    }

    boolean supports(String protocol) { return forProtocol(protocol) != null; }

    @Override public void close() {
        openAi.close();
        anthropic.close();
    }
}
