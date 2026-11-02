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
            // 【安全改造】默认密码不再硬编码/打印到前端可见处：
            // 优先读环境变量；未设置则随机生成一个强密码，仅在服务端控制台一次性输出（不进前端、不进业务日志）。
            String adminPwd = initialPassword("QA_ADMIN_PASSWORD", "admin");
            String userPwd = initialPassword("QA_USER_PASSWORD", "user");
            userService.register("admin", adminPwd, "ADMIN");
            userService.register("user", userPwd, "USER");
            log.info("已初始化默认账号 admin（管理员）、user（普通用户）；初始密码由环境变量提供或已随机生成，请首次登录后立即修改");
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

    /** 取初始密码：有环境变量用之，否则随机生成并只在控制台一次性提示运维 */
    private String initialPassword(String envKey, String account) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String gen = randomPassword();
        // 仅打印到服务端启动控制台（非 Web 页面），供部署者首登后修改；生产建议改用环境变量注入
        System.out.println(">>> 账号 " + account + " 初始密码（仅本次显示，请尽快修改）：" + gen);
        return gen;
    }

    /** 用 SecureRandom 生成 14 位随机强密码（去掉了易混淆字符 0O1lI） */
    private static String randomPassword() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        final String chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
