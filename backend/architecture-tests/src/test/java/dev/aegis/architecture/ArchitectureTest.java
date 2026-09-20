package dev.aegis.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies the production modules against the architecture rules. */
class ArchitectureTest {
    static final String BASE = "dev.aegis";
    static final List<String> CONTEXTS = List.of("identity", "documents", "contracts", "planning", "notifications",
            "operations", "portability", "assistant");
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    void domainAndApplicationAreFrameworkFree() {
        ArchitectureRules.coreIsFrameworkFree(BASE).check(classes);
    }

    @Test
    void dependenciesPointInward() {
        ArchitectureRules.dependenciesPointInward(BASE).check(classes);
        ArchitectureRules.applicationIgnoresAdapters(BASE).check(classes);
    }

    @Test
    void contextsOnlyUseEachOthersPublicApi() {
        for (ArchRule rule : ArchitectureRules.contextsOnlyTalkThroughPublicApi(BASE, CONTEXTS)) {
            rule.check(classes);
        }
    }

    @Test
    void sharedKernelStaysSmallAndIndependent() {
        noClasses().that().resideInAPackage(BASE + ".shared..")
                .should().dependOnClassesThat().resideInAnyPackage(BASE + ".identity..", BASE + ".documents..",
                        BASE + ".contracts..", BASE + ".planning..", BASE + ".notifications..",
                        BASE + ".operations..", BASE + ".portability..", BASE + ".assistant..",
                        BASE + ".bootstrap..")
                .as("shared kernel must not depend on any context").check(classes);
    }

    @Test
    void providerSdkStaysInItsAdapter() {
        ArchitectureRules.vendorSdkStaysInItsAdapter(BASE).check(classes);
    }

    @Test
    void noProcessIsStarted() {
        ArchitectureRules.noProcessExecution(BASE).check(classes);
    }

    @Test
    void persistenceEntitiesNeverLeaveTheirAdapter() {
        noClasses().that().resideOutsideOfPackages(BASE + ".*.adapters.persistence..")
                .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
                .as("JPA entities are private to their persistence adapter").check(classes);
    }
}
