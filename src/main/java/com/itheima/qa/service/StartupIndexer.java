package com.itheima.qa.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动后重建 BM25 索引（DataInitializer 之后执行）。
 * 独立组件避免 IngestService 内部自调用导致 @Transactional 失效。
 */
@Component
@Order(200)
public class StartupIndexer implements CommandLineRunner {

    private final IngestService ingestService;

    public StartupIndexer(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public void run(String... args) {
        ingestService.rebuildAllOnStartup();
        ingestService.reparseAllReadyOnStartup();
    }
}
