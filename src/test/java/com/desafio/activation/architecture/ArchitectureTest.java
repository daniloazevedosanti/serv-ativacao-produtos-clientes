package com.desafio.activation.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Regras de dependência entre camadas, verificadas a cada build. */
@AnalyzeClasses(packages = "com.desafio.activation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.desafio.activation";

    @ArchTest
    static final ArchRule dominio_nao_depende_de_outras_camadas = noClasses()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".application..", ROOT + ".infrastructure..", ROOT + ".web..", ROOT + ".config..",
                    "org.springframework..");

    @ArchTest
    static final ArchRule web_nao_acessa_persistencia_nem_cliente_http_direto = noClasses()
            .that().resideInAPackage(ROOT + ".web..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".infrastructure..");

    @ArchTest
    static final ArchRule aplicacao_nao_conhece_a_camada_web = noClasses()
            .that().resideInAPackage(ROOT + ".application..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".web..");

    @ArchTest
    static final ArchRule infraestrutura_nao_conhece_web = noClasses()
            .that().resideInAPackage(ROOT + ".infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".web..");

    @ArchTest
    static final ArchRule sem_ciclos_entre_pacotes = slices()
            .matching(ROOT + ".(*)..")
            .should().beFreeOfCycles();
}
