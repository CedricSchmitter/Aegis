package dev.aegis.assistant.application.api;

import dev.aegis.assistant.application.api.ChatViews.ChatOverview;
import dev.aegis.assistant.application.api.ChatViews.ConversationView;
import dev.aegis.shared.Actor;
import java.util.UUID;

/** A member's own conversations with the household assistant; other members' conversations are not found. */
public interface ChatQueries {
    ChatOverview overview(Actor actor);

    ConversationView get(Actor actor, UUID conversationId);
}
