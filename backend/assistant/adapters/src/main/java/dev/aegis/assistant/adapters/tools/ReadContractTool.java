package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.shared.ContractId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** One contract with every fact and its provenance, through the contracts context's query contract. */
@Component
public class ReadContractTool implements AiTool {
    private static final ToolSpec SPEC = new ToolSpec(ChatTools.READ_CONTRACT,
            "Reads one contract with every fact: type, value, validity period, review state (only CONFIRMED facts "
                    + "are established), whether it is effective now, and the source document, page and quote. "
                    + "Superseded facts are kept as history. Also lists contradictions and the cancellation "
                    + "deadline with the rule it rests on.",
            Map.of("type", "object",
                    "properties", Map.of("id", Map.of("type", "string",
                            "description", "Contract id from list_contracts")),
                    "required", List.of("id"),
                    "additionalProperties", false));

    private final ContractQueries contracts;

    public ReadContractTool(ContractQueries contracts) {
        this.contracts = contracts;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        UUID id;
        try {
            id = ToolInput.requiredUuid(input, "id");
        } catch (IllegalArgumentException invalid) {
            return ToolInput.invalid(invalid.getMessage());
        }
        return Result.ok(JsonText.write(ContractJson.view(contracts.get(invocation.actor(), new ContractId(id)))));
    }
}
