package com.itheima.qa.config;

import com.itheima.qa.model.User;
import com.itheima.qa.service.UserService;
// jakarta.servlet 是 Java Web 的 Servlet 规范 API（Spring Boot 3 已从 javax.* 迁移到 jakarta.*）
import jakarta.servlet.http.HttpServletRequest;   // 代表一次 HTTP 请求（可读头/参数/会话）
import jakarta.servlet.http.HttpServletResponse;  // 代表响应（可写状态码/错误）
import jakarta.servlet.http.HttpSession;           // 服务端会话：按 JSESSIONID Cookie 区分每个用户
import org.springframework.stereotype.Component;    // 通用组件注解，被组件扫描后发现并注册为 Bean
import org.springframework.web.servlet.HandlerInterceptor; // Spring MVC 拦截器接口

/**
 * 登录拦截器：
 * - /api/auth/** 与静态资源放行；
 * - 其余 /api/** 需登录；
 * - /api/kb（写操作）、/api/admin/** 需 ADMIN 角色（GET 知识库列表普通用户也可用，由 Controller 内判断写操作）。
 * 【讲解要点】HandlerInterceptor 三个钩子：preHandle（Controller 前）、postHandle（Controller 后）、
 * afterCompletion（渲染完成后）。preHandle 返回 false = 请求到此为止，Controller 根本不会执行，
 * 这比在每个方法里写 if (未登录) 高效且不会遗漏 —— 这就是"切面式"权限控制。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final UserService userService;

    // 构造器注入 UserService（当前逻辑其实只用了 session，保留注入是为后续扩展，如 token 校验）
    public AuthInterceptor(UserService userService) {
        this.userService = userService;
    }

    /**
     * preHandle：在 Controller 方法执行"之前"调用。
     * @param req     当前请求对象
     * @param resp    当前响应对象（用于写回 401/403 错误）
     * @param handler 将被执行的目标 Controller 方法信息（这里不用）
     * @return true=放行继续执行 Controller；false=中断请求
     */
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String path = req.getRequestURI();  // 取请求路径，如 /api/kb（不含查询字符串）

        // CORS 预检请求：浏览器跨域调用前会先发一个 OPTIONS 请求探路，
        // 这种请求天然不带登录态，必须直接放行，否则真正的请求永远发不出去
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {  // equalsIgnoreCase：忽略大小写比较
            return true;
        }

        // getSession(false)：只"读取"已存在的 session，没有就返回 null；
        // 【讲解要点】若写 getSession()（无参）会自动创建空 session，这里不需要，所以要传 false
        HttpSession session = req.getSession(false);
        // 从 session 里取登录时放入的 User 对象；getAttribute 返回 Object，需要强转 (User)
        // 三元表达式 x == null ? null : ... —— session 不存在时 user 直接为 null，避免 NPE
        User user = session == null ? null : (User) session.getAttribute(UserService.SESSION_USER);

        if (user == null) {
            // sendError(401, ...)：容器负责输出"未登录"错误响应，返回 false 中断后续流程
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "未登录");
            return false;
        }
        // —— 二级控制：管理端接口限 ADMIN ——
        // 三种情况视为"管理员专属"：
        // 1) /api/admin/** 看板等接口
        // 2) /api/kb 的非 GET 请求（新建/修改/删除知识库；GET 查列表普通用户也需要）
        // 3) /api/documents 全部操作（文档管理整块都是管理员功能）
        boolean adminOnly = path.startsWith("/api/admin")
                || (path.startsWith("/api/kb") && !"GET".equalsIgnoreCase(req.getMethod()))
                || path.startsWith("/api/documents");
        if (adminOnly && !user.isAdmin()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "需要管理员权限"); // 403
            return false;
        }
        return true; // 已登录且权限满足，放行
    }
}
