package dev.aegis.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates that the rules actually reject forbidden dependencies, using deliberately broken fixture classes
 * under {@code dev.aegis.archfixture}. Without this test a green architecture check could be a no-op.
 */
class ArchitectureRulesDetectViolationsTest {
    private static final String FIXTURE_BASE = "dev.aegis.archfixture";
    private final JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURE_BASE);

    @Test
    void jpaAnnotationInDomainIsRejected() {
        var result = ArchitectureRules.coreIsFrameworkFree(FIXTURE_BASE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("LeakyAggregate");
    }

    @Test
    void crossContextAccessToAnotherDomainIsRejected() {
        List<ArchRule> rules = ArchitectureRules.contextsOnlyTalkThroughPublicApi(FIXTURE_BASE,
                List.of("badctx", "otherctx"));

        boolean violated = rules.stream().anyMatch(rule -> rule.evaluate(fixtures).hasViolation());

        assertThat(violated).isTrue();
    }

    @Test
    void providerSdkOutsideItsAdapterIsRejected() {
        var framework = ArchitectureRules.coreIsFrameworkFree(FIXTURE_BASE).evaluate(fixtures);
        var vendor = ArchitectureRules.vendorSdkStaysInItsAdapter(FIXTURE_BASE).evaluate(fixtures);

        assertThat(framework.getFailureReport().toString()).contains("VendorTypedService");
        assertThat(vendor.hasViolation()).isTrue();
        assertThat(vendor.getFailureReport().toString()).contains("VendorTypedService");
    }

    @Test
    void startingProcessesIsRejected() {
        var result = ArchitectureRules.noProcessExecution(FIXTURE_BASE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("ShellingOutAdapter");
    }

    @Test
    void domainDependingOnApplicationIsRejected() {
        var result = ArchitectureRules.dependenciesPointInward(FIXTURE_BASE).evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("UpwardDependency");
    }

    @Test
    void twoRestPayloadsClaimingOneSchemaNameAreRejected() {
        var collisions = ArchitectureRules.restPayloadNameCollisions(fixtures, FIXTURE_BASE);

        assertThat(collisions).containsOnlyKeys("ClashingDto");
        assertThat(collisions.get("ClashingDto"))
                .contains("dev.aegis.archfixture.badctx.adapters.rest.BadCtxPayloads$ClashingDto",
                        "dev.aegis.archfixture.otherctx.adapters.rest.OtherCtxPayloads$ClashingDto");
    }
}
