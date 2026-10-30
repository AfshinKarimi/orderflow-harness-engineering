package com.orderflow.benchmark.oracle;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Hidden copy of the architecture rules of benchmark base commit 69c9d98 (rules 1-10 and 12). The agent cannot
 * change it; it scores both arms the same way.
 * Package patterns use {@code *} for the module name, so new modules are covered automatically.
 */
@AnalyzeClasses(packages = "com.orderflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureOracleTest {

    private static final String BASE = "com.orderflow";
    private static final String PLATFORM = "platform";

    @ArchTest
    static final ArchRule rule1_domainDependsOnlyOnJdkAndDomain =
            classes().that().resideInAPackage(BASE + ".*.domain..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("java..", BASE + ".*.domain..")
                    .because("rule 1: domain is plain Java (JDK, own domain, shared.domain)");

    @ArchTest
    static final ArchRule rule2_applicationDoesNotDependOnAdapters =
            noClasses().that().resideInAPackage(BASE + ".*.application..")
                    .should().dependOnClassesThat().resideInAPackage(BASE + ".*.adapter..")
                    .because("rule 2: application never depends on adapter");

    @ArchTest
    static final ArchRule rule3_applicationUsesOnlyServiceAndTransactional =
            classes().that().resideInAPackage(BASE + ".*.application..")
                    .should().onlyDependOnClassesThat(
                            resideInAnyPackage("java..", BASE + "..", "org.springframework.transaction.annotation..")
                                    .or(equivalentTo(Service.class)))
                    .because("rule 3: only @Service and @Transactional are allowed from Spring in application");

    @ArchTest
    static final ArchRule rule4_inboundAdaptersUsePortsNotServices =
            noClasses().that().resideInAPackage(BASE + ".*.adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage(BASE + ".*.application.service..")
                    .because("rule 4: inbound adapters call the application only through port.in");

    @ArchTest
    static final ArchRule rule4_inboundAdaptersDoNotAccessRepositories =
            noClasses().that().resideInAPackage(BASE + ".*.adapter.in..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            BASE + ".*.application.port.out..", BASE + ".*.adapter.out..")
                    .orShould().dependOnClassesThat().areAssignableTo(Repository.class)
                    .because("rule 4: controllers and listeners go through use cases, never repositories, "
                            + "so domain rules and transaction boundaries cannot be bypassed");

    @ArchTest
    static final ArchRule rule5_adaptersDoNotDependOnEachOther =
            slices().matching(BASE + ".(*).adapter.(*).(*)..")
                    .should().notDependOnEachOther()
                    .because("rule 5: adapters do not depend on each other");

    @ArchTest
    static final ArchRule rule6_noJpaEntitiesInDomain =
            noClasses().that().resideInAPackage(BASE + ".*.domain..")
                    .should().beAnnotatedWith("jakarta.persistence.Entity")
                    .because("rule 6: persistence uses separate JPA entities in adapter.out.persistence");

    @ArchTest
    static final ArchRule rule7and8_otherModulesOnlyViaPublicApiFromInboundAdapters =
            classes().that().resideInAPackage(BASE + "..")
                    .should(accessOtherModulesOnlyViaPublicApiFromInboundAdapters())
                    .because("rules 7 and 8: a module's public API is application.port.in and application.event, "
                            + "used only from the consuming module's adapter.in");

    @ArchTest
    static final ArchRule rule9_noCyclesBetweenModules =
            slices().matching(BASE + ".(*)..")
                    .should().beFreeOfCycles()
                    .because("rule 9: no dependency cycles between modules");

    @ArchTest
    static final ArchRule rule10_sharedDependsOnNoBusinessModule =
            classes().that().resideInAPackage(BASE + ".shared..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("java..", BASE + ".shared..")
                    .because("rule 10: shared is generic and depends on no business module");

    @ArchTest
    static final ArchRule rule12_platformDependsOnNoBusinessModule =
            noClasses().that().resideInAPackage(BASE + ".platform..")
                    .should().dependOnClassesThat(resideInAPackage(BASE + "..")
                            .and(not(resideInAnyPackage(BASE + ".platform..", BASE + ".shared.."))))
                    .because("rule 12: technical platform modules serve business modules and never depend on them "
                            + "(ADR-0003)");

    private static ArchCondition<JavaClass> accessOtherModulesOnlyViaPublicApiFromInboundAdapters() {
        return new ArchCondition<>("depend on other modules only via their public API, from adapter.in") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    String targetModule = moduleOf(target);
                    if (targetModule == null || targetModule.equals(originModule) || targetModule.equals("shared")) {
                        continue;
                    }
                    if (targetModule.equals(PLATFORM)) {
                        // ADR-0003: business modules use only platform.messaging.api, and only from their adapters
                        boolean toPlatformApi = isInPackage(target, BASE + ".platform.messaging.api");
                        boolean fromAdapter = originModule != null
                                && isInPackage(origin, BASE + "." + originModule + ".adapter");
                        if (!toPlatformApi || !fromAdapter) {
                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                        }
                        continue;
                    }
                    String targetApplication = BASE + "." + targetModule + ".application.";
                    boolean toPublicApi = isInPackage(target, targetApplication + "port.in")
                            || isInPackage(target, targetApplication + "event");
                    boolean fromInboundAdapter = originModule != null
                            && isInPackage(origin, BASE + "." + originModule + ".adapter.in");
                    if (!toPublicApi || !fromInboundAdapter) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** Module name is the first package segment below com.orderflow; null for classes outside any module. */
    private static String moduleOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(BASE + ".")) {
            return null;
        }
        String rest = packageName.substring(BASE.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static boolean isInPackage(JavaClass javaClass, String packageName) {
        String actual = javaClass.getPackageName();
        return actual.equals(packageName) || actual.startsWith(packageName + ".");
    }
}
