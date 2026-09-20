package dev.aegis.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the API answers requests that address nothing it serves. Runs on the real MVC wiring -- the SPA resource
 * resolver of {@link WebConfiguration} and {@link ProblemDetailsAdvice} -- with a probe controller instead of the
 * application, so it needs neither a database nor the built frontend. Security is covered by
 * {@code ApiErrorSurfaceIT}, which sends the same requests through the filter chain.
 */
@SpringJUnitWebConfig(classes = ApiErrorResponseTest.WebSlice.class)
class ApiErrorResponseTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final ch.qos.logback.classic.Logger adviceLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp(WebApplicationContext context) {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        logged.start();
        adviceLog.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        adviceLog.detachAppender(logged);
    }

    @Test
    void unknownApiPathIsNotFound() throws Exception {
        MvcResult result = mvc.perform(get("/api/ai")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(problem(result).path("code").asString()).isEqualTo("request.pathNotFound");
        assertThat(problem(result).path("status").asInt()).isEqualTo(404);
        assertThat(problem(result).path("detail").asString()).isNotEmpty();
        assertThat(logged.list).as("an unknown path is a client mistake, not a failure to investigate").isEmpty();
    }

    @Test
    void theBareApiPrefixIsNotFoundEitherRatherThanTheApplication() throws Exception {
        MvcResult result = mvc.perform(get("/api")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(problem(result).path("code").asString()).isEqualTo("request.pathNotFound");
    }

    @Test
    void unsupportedMethodOnAKnownPathIsMethodNotAllowed() throws Exception {
        MvcResult result = mvc.perform(post("/api/probe")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getHeader("Allow")).contains("GET");
        assertThat(problem(result).path("code").asString()).isEqualTo("request.methodNotAllowed");
        assertThat(logged.list).isEmpty();
    }

    @Test
    void unsupportedMediaTypeOnAKnownPathIsUnsupportedMediaType() throws Exception {
        MvcResult result = mvc.perform(post("/api/probe/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(415);
        assertThat(problem(result).path("code").asString()).isEqualTo("request.unsupportedMediaType");
        assertThat(logged.list).isEmpty();
    }

    @Test
    void anUnexpectedFailureStaysAnInternalErrorWithACorrelationId() throws Exception {
        MvcResult result = mvc.perform(get("/api/probe/boom")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(problem(result).path("code").asString()).isEqualTo("internal.error");
        String correlationId = problem(result).path("correlationId").asString();
        assertThat(correlationId).isNotEmpty();
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains(correlationId);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void aFrontendRouteStillServesTheApplication() throws Exception {
        MvcResult result = mvc.perform(get("/documents/inbox")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("<div id=\"root\">");
    }

    @Test
    void anExistingAssetIsServedAndAMissingOneIsNotFound() throws Exception {
        assertThat(mvc.perform(get("/assets/app.css")).andReturn().getResponse().getStatus()).isEqualTo(200);

        MvcResult missing = mvc.perform(get("/assets/gone.css")).andReturn();

        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(problem(missing).path("code").asString()).isEqualTo("request.pathNotFound");
        assertThat(logged.list).isEmpty();
    }

    private static JsonNode problem(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** The production MVC wiring minus the application: same resolver and advice, a stand-in for the built app. */
    @Configuration
    @EnableWebMvc
    static class WebSlice implements WebMvcConfigurer {
        @Bean
        ProblemDetailsAdvice problemDetailsAdvice() {
            return new ProblemDetailsAdvice();
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/spa-test/").resourceChain(false)
                    .addResolver(new SpaResourceResolver());
        }
    }

    /** Stands in for the API: one known path with one method, one JSON-only path, one that fails. */
    @RestController
    static class ProbeController {
        @GetMapping("/api/probe")
        String probe() {
            return "probe";
        }

        @PostMapping(path = "/api/probe/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody String payload) {
            return payload;
        }

        @GetMapping("/api/probe/boom")
        String boom() {
            throw new IllegalStateException("synthetic failure");
        }
    }
}
