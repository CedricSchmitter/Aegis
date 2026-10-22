package dev.aegis.portability.adapters.json;

import dev.aegis.portability.application.ports.ExportSerializer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** Pretty-printed, ISO-dated JSON for export files. */
@Component
public class JacksonExportSerializer implements ExportSerializer {
    private final ObjectMapper mapper;
    private final ObjectMapper canonical;

    public JacksonExportSerializer(ObjectMapper mapper) {
        // Jackson 3 writes java.time values as ISO-8601 strings by default.
        this.mapper = mapper.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
        this.canonical = mapper.rebuild().disable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    @Override
    public byte[] toCanonicalJson(Object value) {
        return canonical.writeValueAsBytes(value);
    }

    @Override
    public byte[] toJson(Object value) {
        return mapper.writeValueAsBytes(value);
    }

    @Override
    public <T> T fromJson(byte[] json, Class<T> type) {
        return mapper.readValue(json, type);
    }
}
