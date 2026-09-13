package dev.aegis.documents.adapters.paperless;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Builds the HTTP client for the Paperless adapter with bounded timeouts and basic authentication. */
@Configuration
@EnableConfigurationProperties(PaperlessProperties.class)
class PaperlessClientConfiguration {

    @Bean
    RestClient paperlessRestClient(RestClient.Builder builder, PaperlessProperties properties) {
        var settings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        // The simple (HttpURLConnection) factory buffers request bodies and sends a Content-Length; Paperless
        // rejects chunked multipart uploads with 400.
        var factory = ClientHttpRequestFactoryBuilder.simple().build(settings);
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeaders(headers -> {
                    headers.setBasicAuth(properties.username(), properties.password());
                    headers.set("Accept", PaperlessClient.ACCEPT_V10);
                })
                .build();
    }
}
