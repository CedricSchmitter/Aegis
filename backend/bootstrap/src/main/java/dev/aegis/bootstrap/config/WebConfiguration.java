package dev.aegis.bootstrap.config;

import dev.aegis.identity.adapters.security.ActorArgumentResolver;
import java.util.List;
import java.util.Map;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC wiring: actor resolution for controllers and same-origin SPA serving with history-API fallback. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final ActorArgumentResolver actorArgumentResolver;

    public WebConfiguration(ActorArgumentResolver actorArgumentResolver) {
        this.actorArgumentResolver = actorArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(actorArgumentResolver);
    }

    /** Static resources take their type from the container; the web app manifest is unknown to its defaults. */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webManifestMimeType() {
        return factory -> factory.addMimeMappings(new MimeMappings(Map.of("webmanifest", "application/manifest+json")));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations("classpath:/static/").resourceChain(true)
                .addResolver(new SpaResourceResolver());
    }
}
