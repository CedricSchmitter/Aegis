package dev.aegis.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;

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
     * Aegis never starts processes: no shell, no CLI subprocess and no command the model could influence. AI work
     * goes through model gateways and application tools only.
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
                .as("Aegis code must not start operating-system processes");
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
}
