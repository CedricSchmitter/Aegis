package dev.aegis.assistant.adapters.rest;

import dev.aegis.assistant.adapters.rest.ChatDtos.AskQuestionRequest;
import dev.aegis.assistant.adapters.rest.ChatDtos.ChatOverviewDto;
import dev.aegis.assistant.adapters.rest.ChatDtos.ConversationDto;
import dev.aegis.assistant.application.api.ChatCommands;
import dev.aegis.assistant.application.api.ChatQueries;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own conversations with the household assistant. Asking starts a run in the background and returns at
 * once; the client polls the conversation until the question is answered, failed or cancelled.
 */
@RestController
@RequestMapping("/api/chat")
@Tag(name = "chat")
public class ChatController {
    private final ChatQueries queries;
    private final ChatCommands commands;

    public ChatController(ChatQueries queries, ChatCommands commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @Operation(operationId = "chatOverview", summary = "Whether the member can ask now, and their conversations")
    @GetMapping("/conversations")
    public ChatOverviewDto overview(Actor actor) {
        return ChatOverviewDto.from(queries.overview(actor));
    }

    @Operation(operationId = "startConversation", summary = "Start a conversation with its first question",
            description = "Idempotent for the same conversation id, turn id and question.")
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto start(Actor actor, @RequestBody AskQuestionRequest body) {
        return ConversationDto.from(commands.start(actor, body.conversationId(), body.turnId(), body.question(),
                body.locale()));
    }

    @Operation(operationId = "getConversation", summary = "One of the member's conversations with every question")
    @GetMapping("/conversations/{id}")
    public ConversationDto get(Actor actor, @PathVariable UUID id) {
        return ConversationDto.from(queries.get(actor, id));
    }

    @Operation(operationId = "askQuestion", summary = "Ask a further question in a conversation",
            description = "Refused with chat.turnActive while the previous question is being answered. Idempotent "
                    + "for the same turn id and question.")
    @PostMapping("/conversations/{id}/turns")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto ask(Actor actor, @PathVariable UUID id, @RequestBody AskQuestionRequest body) {
        return ConversationDto.from(commands.ask(actor, id, body.turnId(), body.question(), body.locale()));
    }

    @Operation(operationId = "cancelQuestion", summary = "Stop answering a question")
    @PostMapping("/conversations/{id}/turns/{turnId}/cancel")
    public ConversationDto cancel(Actor actor, @PathVariable UUID id, @PathVariable UUID turnId) {
        return ConversationDto.from(commands.cancel(actor, id, turnId));
    }

    @Operation(operationId = "retryQuestion", summary = "Ask the latest question again after it failed or was cancelled")
    @PostMapping("/conversations/{id}/turns/{turnId}/retry")
    public ConversationDto retry(Actor actor, @PathVariable UUID id, @PathVariable UUID turnId) {
        return ConversationDto.from(commands.retry(actor, id, turnId));
    }

    @Operation(operationId = "deleteConversation", summary = "Delete a conversation")
    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Actor actor, @PathVariable UUID id) {
        commands.delete(actor, id);
    }
}
