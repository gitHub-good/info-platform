package com.info.platform.infrastructure.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * AOP 装配（基础设施层横切，T33）：显式开启基于注解的 AspectJ 自动代理（CGLIB 子类代理）。
 *
 * <p>承载 {@link JobExecutionAspect} 等横切切面的织入。Spring Boot 经 {@code spring-boot-starter-aop} 的 {@code
 * AopAutoConfiguration} 默认也会开启本能力；此处显式声明使 AOP 织入对代码库可见、不依赖隐式自动配置， {@code AopConfigUtils}
 * 对自动代理创建器去重，重复声明不会冲突。
 *
 * <p>{@code proxyTargetClass = true}：用 CGLIB 子类代理（不要求目标类实现接口），契合各 Job 均为具体类的现状。
 */
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class AopConfig {}
