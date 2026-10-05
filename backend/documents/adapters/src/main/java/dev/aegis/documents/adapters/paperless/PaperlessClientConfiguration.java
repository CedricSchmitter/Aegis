package dev.aegis.documents.adapters.paperless;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Builds the HTTP client for the Paperless adapter with bounded timeouts and basic authentication. */
@Configuration
@EnableConfigurationProperties(PaperlessProperties.class)
class PaperlessClientConfiguration {

    /**
     * One search page may use the whole traversal budget: the pager already stops at its overall deadline, and a
     * shorter per-page timeout turns a slow first page into an unavailable search. On the household VM a page took
     * about 0.6 s when measured idle (October 2026), so a fixed 3 s left little room for load such as OCR.
     */
    @Bean
    RestClient paperlessSearchRestClient(
            @Qualifier("paperlessRestClient") RestClient client) {
        var settings = HttpClientSettings.defaults().withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(ArchiveSearchPager.MAX_TIME)
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        return client.mutate().requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings)).build();
    }

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
