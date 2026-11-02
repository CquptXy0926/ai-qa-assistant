package com.itheima.qa.config;

import com.itheima.qa.rag.Bm25Index;
// @Bean：方法级注解，返回值会被注册为 Spring 容器中的一个 Bean（单例），
// 方法名默认作为 Bean 的名字；@Configuration 标记"这个类是 Java 形式的配置类"
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// Spring 提供的线程池实现（JDK ThreadPoolExecutor 的增强版），支持优雅停机、指标暴露
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;   // JDK 并程池顶层接口：只有 execute(Runnable) 一个方法

/**
 * 集中声明"无法靠 @Component 注解自动注册、需要手动 new 出来"的 Bean。
 * 【讲解要点】为什么要手动注册 Bm25Index？因为它是纯 POJO（没加 @Component），
 * 而且构造方式由这里控制（每 JVM 一个共享单例），符合"配置与业务类分离"的惯例。
 */
@Configuration
public class AppBeans {

    /**
     * BM25 内存检索索引，全应用共享一个实例（@Bean 方法默认返回 singleton 单例）。
     * 所有 Service 注入的都是同一个对象，保证检索看到的数据一致。
     */
    @Bean
    public Bm25Index bm25Index() {
        return new Bm25Index();
    }

    /**
     * 文档解析流水线线程池。
     * @Bean("ingestExecutor")：显式指定 Bean 名字，避免和其他 Executor 类型 Bean 冲突。
     * 【讲解要点】为什么要自定义线程池而不用默认的？
     * - 解析 PDF/Word 是 CPU+IO 混合的重活，必须限制并发数，防止大文件洪峰打满服务器；
     * - 命名线程（ingest-1、ingest-2）方便看日志定位问题。
     */
    @Bean("ingestExecutor")
    public Executor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);      // 核心线程数：常驻 2 个工人，即使空闲也不销毁
        executor.setMaxPoolSize(4);       // 最大线程数：队列满了以后最多临时扩到 4 个工人
        executor.setQueueCapacity(100);   // 排队队列：最多 100 个任务排队，再满就拒绝（保护系统）
        executor.setThreadNamePrefix("ingest-"); // 线程名前缀：日志里显示 ingest-1，便于排查
        executor.initialize();            // 手动初始化（作为 @Bean 返回时 Spring 也会管理生命周期，
                                          // 但这里显式调用确保线程池在返回前就绪）
        return executor;
    }
}
