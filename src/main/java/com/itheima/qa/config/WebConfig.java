package com.itheima.qa.config;

import org.springframework.context.annotation.Configuration;
// InterceptorRegistry：拦截器注册表，addInterceptor() 返回的 Registration 支持链式配置
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
// WebMvcConfigurer：Spring MVC 的"定制钩子"接口。
// 【讲解要点】它的所有方法都有 default 空实现，我们只覆写关心的那一个 ——
// 这是接口"默认方法"（Java 8+）的典型应用，避免实现类被迫写一堆空方法
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 全局配置：把登录拦截器注册进请求处理链。
 * 【讲解要点】Spring MVC 处理一个请求的顺序：
 * Filter → DispatcherServlet → Interceptor.preHandle → Controller → preHandle 返回 false 则直接中断
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /**
     * 构造器注入（全项目统一风格）：
     * 【讲解要点】Spring 4.3+ 起，单构造器类可以不加 @Autowired，
     * 容器启动时自动按参数类型找到 AuthInterceptor Bean 注入。
     * final 字段 + 构造器注入 = 不可变、必然非空、方便写单测，是官方推荐做法。
     */
    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)          // 注册拦截器
                .addPathPatterns("/api/**")               // 只拦截 /api/ 开头的所有路径（Ant 风格通配符）
                .excludePathPatterns(                     // 例外放行：仅登录接口本身不需要登录态；注册接口已下线
                        "/api/auth/login");
    }
}
