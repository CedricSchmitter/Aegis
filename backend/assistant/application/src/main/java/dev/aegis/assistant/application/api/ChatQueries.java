package dev.aegis.assistant.application.api;

import dev.aegis.assistant.application.api.ChatViews.ChatOverview;
import dev.aegis.assistant.application.api.ChatViews.ConversationSummary;
import dev.aegis.assistant.application.api.ChatViews.ConversationView;
import dev.aegis.shared.Actor;
import java.util.List;
import java.util.UUID;

/** A member's own conversations with the household assistant; other members' conversations are not found. */
public interface ChatQueries {
    ChatOverview overview(Actor actor);

    /**
     * The member's conversations that contain every word of the text in their title, questions or answers, ignoring
     * case, most recently updated first. Blank text finds the most recent conversations.
     */
    List<ConversationSummary> search(Actor actor, String text);

    ConversationView get(Actor actor, UUID conversationId);
}
