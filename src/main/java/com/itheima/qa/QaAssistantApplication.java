package com.itheima.qa;

// SpringApplication：Spring Boot 的核心启动器类，run() 方法负责创建并启动整个应用上下文（容器）
import org.springframework.boot.SpringApplication;
// @SpringBootApplication 是一个"三合一"注解，等价于同时标注下面三个注解：
// 1) @SpringBootConfiguration —— 标记这是一个配置类（本质是 @Configuration）
// 2) @EnableAutoConfiguration —— 开启"自动装配"：Spring Boot 根据 pom.xml 里引入的依赖
//    （如 spring-boot-starter-web），自动创建 Tomcat、DispatcherServlet、数据源等成千上万个 Bean，
//    这就是"约定优于配置"的核心体现
// 3) @ComponentScan —— 从当前类所在包（com.itheima.qa）开始，递归扫描所有子包，
//    把标注了 @Component/@Service/@RestController 等注解的类自动注册为 Bean
import org.springframework.boot.autoconfigure.SpringBootApplication;
// @ConfigurationPropertiesScan：告诉 Spring 去扫描标注了 @ConfigurationProperties 的类
// （如本项目的 LlmProperties、RagProperties），自动把 application.yml 中 app.* 的配置值绑定进去，
// 这样就不需要在每个配置类上单独写 @EnableConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 智能问答助手（企业知识库 RAG）启动类。
 * 【讲解要点】Spring Boot 应用的唯一入口：main 方法 → SpringApplication.run() →
 * 内嵌 Tomcat 启动 + 自动装配 + 组件扫描，一行的区别都没有，整个 Web 服务器就跑起来了。
 */
@SpringBootApplication      // 三合一注解：配置类 + 开启自动装配 + 组件扫描
@ConfigurationPropertiesScan // 扫描 @ConfigurationProperties 配置绑定类（RagProperties / LlmProperties）
public class QaAssistantApplication {

    // Java 程序的标准主方法：public static void main(String[] args)
    // args 是命令行参数数组，比如 java -jar xxx.jar --server.port=9090 时这里就能收到该参数
    public static void main(String[] args) {
        // 第一个参数：主配置类的 Class 对象（Spring 以它为扫描/装配的起点）
        // 第二个参数：命令行参数，Spring Boot 会把 --key=value 形式的参数也纳入配置体系
        // run() 的返回值是 ConfigurableApplicationContext（容器对象），本项目没有接收它，直接让应用在后台运行
        SpringApplication.run(QaAssistantApplication.class, args);
    }
}
