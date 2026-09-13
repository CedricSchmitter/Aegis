package dev.aegis.bootstrap.config;

import dev.aegis.shared.Actor;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the generated contract precise for the TypeScript client: the resolved {@link Actor} is not a request
 * parameter, and every DTO property is present in responses (Jackson writes explicit nulls), so properties are
 * required unless a schema marks them nullable.
 */
@Configuration
public class OpenApiConfiguration {
    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor.class);
    }

    @Bean
    OpenApiCustomizer requiredUnlessNullable() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
                Map<String, Schema> properties = schema.getProperties();
                if (properties == null) {
                    continue;
                }
                List<String> required = new ArrayList<>();
                for (Map.Entry<String, Schema> property : properties.entrySet()) {
                    if (!isNullable(property.getValue())) {
                        required.add(property.getKey());
                    }
                }
                schema.setRequired(required);
            }
        };
    }

    private static boolean isNullable(Schema<?> property) {
        if (Boolean.TRUE.equals(property.getNullable())) {
            return true;
        }
        return property.getTypes() != null && property.getTypes().contains("null");
    }
}
