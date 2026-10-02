package dev.aegis.assistant.adapters.tools;

import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.chat.ChatTools;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.contracts.application.api.ContractViews.ContractSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The household's contracts with their cancellation state, through the contracts context's query contract. */
@Component
public class ListContractsTool implements AiTool {
    static final int MAX_CONTRACTS = 100;

    private static final ToolSpec SPEC = new ToolSpec(ChatTools.LIST_CONTRACTS,
            "Lists the household's contracts (not in the trash): id, name, counterparty, status, contract end and "
                    + "the cancellation deadline as far as confirmed facts establish it, or what is missing to "
                    + "calculate it, plus the number of unconfirmed facts and contradictions.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final ContractQueries contracts;

    public ListContractsTool(ContractQueries contracts) {
        this.contracts = contracts;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Result invoke(Invocation invocation, Map<String, Object> input) {
        List<ContractSummary> all = contracts.list(invocation.actor());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ContractSummary contract : all.subList(0, Math.min(all.size(), MAX_CONTRACTS))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", contract.id().toString());
            item.put("name", contract.name());
            item.put("counterparty", contract.counterparty());
            item.put("status", contract.status());
            item.put("contractEnd", ContractJson.text(contract.contractEnd()));
            item.put("cancellation", ContractJson.deadline(contract.deadline()));
            item.put("unconfirmedFacts", contract.pendingFacts());
            item.put("contradictions", contract.conflicts());
            items.add(item);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("contracts", items);
        answer.put("omitted", Math.max(0, all.size() - MAX_CONTRACTS));
        return Result.ok(JsonText.write(answer));
    }
}
