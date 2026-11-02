package com.itheima.qa.config;

import com.itheima.qa.model.KnowledgeBase;
import com.itheima.qa.repo.KbRepository;
import com.itheima.qa.service.UserService;
// SLF4J：Java 日志"门面"标准（只有接口），运行时由 Logback 等实现真正输出。
// 【讲解要点】业界规范永远是 import org.slf4j.*，而不是 System.out.println
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// CommandLineRunner：函数式接口，Spring Boot 启动"完成前"（容器就绪后）自动调用其 run() 一次，
// 是做"启动初始化数据"的标准姿势
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时初始化默认账号与示例知识库。
 * 【讲解要点】这类组件俗称"数据种子（Seed Data）"，保证系统部署到空环境也可用。
 * 类上不需要 @Transactional：userRepository/kbRepository 的 save 方法自带事务。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    // Logger 声明惯例：static final + 传当前类的 Class，日志里能显示来源类名
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserService userService;
    private final KbRepository kbRepository;

    // 构造器注入两个依赖（Spring 自动把容器中的对应 Bean 传进来）
    public DataInitializer(UserService userService, KbRepository kbRepository) {
        this.userService = userService;
        this.kbRepository = kbRepository;
    }

    /**
     * run(String... args)：varargs（可变参数）语法，调用方可传 0 个或任意多个字符串。
     * 启动顺序由 @Order 控制（本类默认 0，先于 StartupIndexer 的 @Order(200) 执行）。
     */
    @Override
    public void run(String... args) {
        if (!userService.hasAnyUser()) {
            // 【初始密码策略】不再随机生成（随机密码只打印在启动控制台，运维容易漏看导致无法登录）：
            // 优先读环境变量注入；未设置时回落到固定默认值，本地首次启动可直接用 admin / admin123 登录。
            // 密码原文绝不写入任何日志、绝不返回前端；加密统一走 UserService.register -> hash（SHA-256 加盐），
            // 与普通账号创建、自助改密使用的是同一套逻辑，此处不做任何改动。
            // 生产环境务必通过环境变量覆盖默认密码，并在首次登录后立即修改。
            String adminPwd = initialPassword("QA_ADMIN_PASSWORD", "admin123");
            String userPwd = initialPassword("QA_USER_PASSWORD", "user123");
            userService.register("admin", adminPwd, "ADMIN");
            userService.register("user", userPwd, "USER");
            // 注意：这里只记录账号初始化事实，绝不打印密码本身
            log.info("已初始化默认账号 admin（管理员）、user（普通用户）；密码由环境变量提供，未设置时使用默认初始密码，请首次登录后立即修改");
        }
        for (String name : new String[]{"公司制度", "产品手册", "FAQ"}) {
            if (kbRepository.findByName(name).isEmpty()) {
                KnowledgeBase kb = new KnowledgeBase();
                kb.setName(name);
                kb.setDescription("默认知识库：" + name);
                kbRepository.save(kb);
            }
        }
    }

    /**
     * 取初始密码：有环境变量则用之，否则回落到传入的默认密码。
     * 无论走哪个分支，密码原文都不落日志、不进控制台输出。
     */
    private String initialPassword(String envKey, String defaultPwd) {
        String fromEnv = System.getenv(envKey);
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : defaultPwd;
    }
}
