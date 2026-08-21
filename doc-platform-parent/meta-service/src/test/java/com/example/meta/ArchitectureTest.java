package com.example.meta;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * meta-service 架构守护（ArchUnit，design/08 §四分层与防腐约束）：
 * <ol>
 *   <li>四层单向依赖：controller → service → domain ← infrastructure，domain 不反向依赖；</li>
 *   <li>跨服务仅依赖对方 api 包（A-04，禁止直读 runtime 内部实现）；</li>
 *   <li>domain/service 禁止 import Tablestore / RocketMQ SDK（一律经 doc-common 防腐层收口，F-06）；</li>
 *   <li>包之间无循环依赖。</li>
 * </ol>
 */
@AnalyzeClasses(packages = "com.example.meta", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    /** 四层单向依赖（controller→service→domain←infrastructure） */
    @ArchTest
    static final ArchRule layered_dependencies = Architectures.layeredArchitecture()
            .consideringAllDependencies()
            .layer("Controller").definedBy("com.example.meta.controller..")
            .layer("Service").definedBy("com.example.meta.service..")
            .layer("Domain").definedBy("com.example.meta.domain..")
            .layer("Infrastructure").definedBy("com.example.meta.infrastructure..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Controller", "Service", "Infrastructure")
            .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Controller", "Service");

    /** domain 不反向依赖 controller / service / infrastructure */
    @ArchTest
    static final ArchRule domain_isolation = noClasses()
            .that().resideInAPackage("com.example.meta.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.example.meta.controller..",
                    "com.example.meta.service..",
                    "com.example.meta.infrastructure..");

    /** 跨服务仅依赖 runtime 的 api 包（A-04） */
    @ArchTest
    static final ArchRule cross_service_only_api = noClasses()
            .that().resideInAPackage("com.example.meta..")
            .should().dependOnClassesThat(
                    resideInAPackage("com.example.runtime..")
                            .and(resideOutsideOfPackage("com.example.runtime.api..")));

    /** domain/service 禁 import Tablestore SDK（防腐层唯一入口，F-06/TS-01） */
    @ArchTest
    static final ArchRule no_tablestore_sdk_in_domain_service = noClasses()
            .that().resideInAnyPackage("com.example.meta.domain..", "com.example.meta.service..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.alicloud.openservices.tablestore..");

    /** domain/service 禁 import RocketMQ SDK（消息收发经 infrastructure 装配） */
    @ArchTest
    static final ArchRule no_rocketmq_sdk_in_domain_service = noClasses()
            .that().resideInAnyPackage("com.example.meta.domain..", "com.example.meta.service..")
            .should().dependOnClassesThat()
            .resideInAPackage("org.apache.rocketmq..");

    /** 业务类不得依赖 SDK（controller 层同样禁止直连存储） */
    @ArchTest
    static final ArchRule no_storage_sdk_in_controller = noClasses()
            .that().resideInAPackage("com.example.meta.controller..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.alicloud.openservices.tablestore..");

    /** 无循环包依赖 */
    @ArchTest
    static final ArchRule no_package_cycles = SlicesRuleDefinition.slices()
            .matching("com.example.meta.(*)..")
            .should().beFreeOfCycles();

    /** 控制器命名约定（Controller 后缀，保证分层扫描准确） */
    @ArchTest
    static final ArchRule controller_naming = classes()
            .that().resideInAPackage("com.example.meta.controller..")
            .and(com.tngtech.archunit.base.DescribedPredicate.not(
                    com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName("package-info")))
            .should().haveSimpleNameEndingWith("Controller");
}
