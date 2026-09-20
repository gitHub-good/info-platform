package com.info.platform.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * DDD 分层依赖校验（T32）：领域层纯净不引框架、禁跨层调用、接口层不直依赖基础设施实现、禁止循环依赖。
 *
 * <p>规则随代码演进持续守护：新增类违反任一规则，本测试即红，强制整改。
 */
@AnalyzeClasses(packages = "com.info.platform")
class LayeredArchitectureTest {

    /** 领域层不得依赖接口层/应用层/基础设施层（依赖只能向下，领域层是稳定核心）。 */
    @ArchTest
    static final ArchRule domain_should_not_depend_on_outer_layers =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..");

    /** 领域层保持纯净：不 import Spring/MyBatis/MyBatis-Plus/Jackson/Servlet 等框架类型。 */
    @ArchTest
    static final ArchRule domain_should_not_import_framework =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "org.apache.ibatis..",
                            "com.baomidou..",
                            "com.fasterxml.jackson..",
                            "jakarta.servlet..");

    /** 接口层（Controller）不得直接依赖基础设施层实现类，只依赖 domain/application 端口。 */
    @ArchTest
    static final ArchRule interfaces_should_not_depend_on_infrastructure =
            noClasses().that().resideInAPackage("..interfaces..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure..");

    /** 禁止层间循环依赖（按 com.info.platform 下第一级包切片）。 */
    @ArchTest
    static final ArchRule no_cyclic_dependencies_between_layers =
            slices().matching("com.info.platform.(*)..")
                    .should().beFreeOfCycles();
}
