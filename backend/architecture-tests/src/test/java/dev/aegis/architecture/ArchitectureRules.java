package dev.aegis.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Architecture rules parameterized by base package so the same rules can be verified against the production code
 * and against deliberately broken fixture classes.
 */
final class ArchitectureRules {
    static final String[] FRAMEWORK_PACKAGES = {"org.springframework..", "jakarta.persistence..",
        "jakarta.servlet..", "jakarta.validation..", "tools.jackson..", "com.fasterxml..", "org.hibernate..",
        "com.github.kagkarlsson..", "io.swagger..", "org.slf4j..", "com.anthropic..", "okhttp3..", "kotlin.."};

    private ArchitectureRules() {
    }

    /** Domain and application code must stay plain Java. */
    static ArchRule coreIsFrameworkFree(String base) {
        return noClasses().that().resideInAnyPackage(base + ".*.domain..", base + ".*.application..")
                .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .as("domain and application must not depend on Spring, JPA, HTTP, Jackson or vendor libraries");
    }

    /** Dependencies point inward: domain knows nothing outside, application knows no adapters. */
    static ArchRule dependenciesPointInward(String base) {
        return noClasses().that().resideInAPackage(base + ".*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(base + ".*.application..", base + ".*.adapters..",
                        base + ".bootstrap..")
                .as("domain must not depend on application, adapters or bootstrap");
    }

    static ArchRule applicationIgnoresAdapters(String base) {
        return noClasses().that().resideInAPackage(base + ".*.application..")
                .should().dependOnClassesThat().resideInAnyPackage(base + ".*.adapters..", base + ".bootstrap..")
                .as("application must not depend on adapters or bootstrap");
    }

    /** Model provider SDKs stay inside their provider adapter package, so replacing a provider touches one place. */
    static ArchRule vendorSdkStaysInItsAdapter(String base) {
        return noClasses().that().resideOutsideOfPackage(base + ".assistant.adapters.anthropic..")
                .should().dependOnClassesThat().resideInAnyPackage("com.anthropic..")
                .as("only the Anthropic adapter may use the Anthropic SDK");
    }

    /**
     * Aegis starts no operating-system processes (ADR-0003): no shell and no
     * command the model could influence. AI work goes through model gateways and application tools only.
     */
    static ArchRule noProcessExecution(String base) {
        return noClasses().that().resideInAPackage(base + "..")
                .should().dependOnClassesThat().areAssignableTo(ProcessBuilder.class)
                .orShould().callMethod(Runtime.class, "exec", String.class)
                .orShould().callMethod(Runtime.class, "exec", String[].class)
                .orShould().callMethod(Runtime.class, "exec", String.class, String[].class)
                .orShould().callMethod(Runtime.class, "exec", String[].class, String[].class)
                .orShould().callMethod(Runtime.class, "exec", String.class, String[].class, java.io.File.class)
                .orShould().callMethod(Runtime.class, "exec", String[].class, String[].class, java.io.File.class)
                .as("application code must not start operating-system processes");
    }

    /**
     * A bounded context may use another context only through that context's {@code application.api} package.
     * Domain models, repositories and adapters of other contexts are off limits; the composition root is exempt.
     */
    static List<ArchRule> contextsOnlyTalkThroughPublicApi(String base, List<String> contexts) {
        List<ArchRule> rules = new ArrayList<>();
        for (String context : contexts) {
            List<String> otherInternals = new ArrayList<>();
            List<String> otherApis = new ArrayList<>();
            for (String other : contexts) {
                if (!other.equals(context)) {
                    otherInternals.add(base + "." + other + "..");
                    otherApis.add(base + "." + other + ".application.api..");
                }
            }
            if (otherInternals.isEmpty()) {
                continue;
            }
            DescribedPredicate<JavaClass> forbidden = resideInAnyPackage(otherInternals.toArray(String[]::new))
                    .and(not(resideInAnyPackage(otherApis.toArray(String[]::new))));
            rules.add(noClasses().that().resideInAPackage(base + "." + context + "..")
                    .should().dependOnClassesThat(forbidden)
                    .as("context '" + context + "' may only use other contexts through their application.api"));
        }
        return rules;
    }

    private static final String SCHEMA_ANNOTATION = "io.swagger.v3.oas.annotations.media.Schema";
    private static final String[] PAYLOAD_SUFFIXES = {"Dto", "Request", "Response"};

    /**
     * REST payload types of all contexts share one flat OpenAPI schema namespace, and springdoc silently keeps
     * only one class per name. A collision publishes another context's fields, so the generated TypeScript client
     * describes the wrong payload. Returns every schema name claimed by more than one class, so the rule reports
     * the collision instead of letting it reach the contract.
     */
    static SortedMap<String, List<String>> restPayloadNameCollisions(JavaClasses classes, String base) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (JavaClass type : classes) {
            if (!type.getPackageName().startsWith(base) || !type.getPackageName().contains(".adapters.rest")
                    || !type.getModifiers().contains(JavaModifier.PUBLIC)) {
                continue;
            }
            String simple = type.getSimpleName();
            boolean payload = false;
            for (String suffix : PAYLOAD_SUFFIXES) {
                payload |= simple.endsWith(suffix);
            }
            if (!payload) {
                continue;
            }
            byName.computeIfAbsent(schemaName(type), key -> new ArrayList<>()).add(type.getName());
        }
        SortedMap<String, List<String>> collisions = new TreeMap<>();
        byName.forEach((name, owners) -> {
            if (owners.size() > 1) {
                collisions.put(name, owners);
            }
        });
        return collisions;
    }

    /** The name springdoc publishes: an explicit {@code @Schema(name = ...)}, otherwise the simple class name. */
    private static String schemaName(JavaClass type) {
        Optional<Object> explicit = type.getAnnotations().stream()
                .filter(annotation -> annotation.getRawType().getName().equals(SCHEMA_ANNOTATION))
                .findFirst().flatMap(annotation -> annotation.get("name"));
        return explicit.map(Object::toString).filter(name -> !name.isBlank()).orElse(type.getSimpleName());
    }
}
