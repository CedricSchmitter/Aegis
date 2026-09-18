package dev.aegis.archfixture.badctx.application;

import com.anthropic.models.messages.Message;

/** Fixture: an application service that leaks a provider SDK type. Must be rejected by the rules. */
public class VendorTypedService {
    public String firstId(Message message) {
        return message.id();
    }
}
