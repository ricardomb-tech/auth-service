package com.auth_service.auth.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the dependency rule of the Clean Architecture spine (AD-1, AD-12,
 * AD-13): infrastructure -> application -> domain, never the other way
 * around, and domain stays framework-free.
 *
 * <p>This test must break the build the moment a future story violates one
 * of these rules — it is not merely documentation.</p>
 */
@AnalyzeClasses(packages = "com.auth_service.auth")
public class ArchitectureRulesTest {

    // allowEmptyShould(true): domain/ y application/ están vacíos en esta historia
    // (Story 1.1 solo monta el esqueleto); las reglas deben poder evaluarse sin
    // romper el build por ausencia de clases, pero seguirán aplicando en cuanto
    // la Story 1.2 en adelante empiece a poblar esos paquetes.

    @ArchTest
    static final ArchRule domain_should_not_depend_on_application_or_infrastructure = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_should_be_framework_free = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule application_should_not_depend_on_infrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);
}
