package com.itheima.qa.web;

import com.itheima.qa.model.User;
import com.itheima.qa.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口:登录/登出/当前用户/管理员改密。
 * 【变更说明】不再提供公开的 /register 接口,普通账号统一由管理员在后台创建。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginReq(String username, String password) {}
    /** 修改自己密码:旧密码校验 + 新密码 */
    public record ChangePwdReq(String oldPassword, String newPassword) {}

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req, HttpServletRequest request) {
        User user = userService.login(req.username(), req.password());
        if (user == null) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        request.getSession(true).setAttribute(UserService.SESSION_USER, user);
        return Map.of("username", user.getUsername(), "role", user.getRole());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        User user = session == null ? null : (User) session.getAttribute(UserService.SESSION_USER);
        if (user == null) {
            return Map.of("logged", false);
        }
        return Map.of("logged", true, "username", user.getUsername(), "role", user.getRole());
    }

    /**
     * 管理员修改自己的登录密码。普通用户无此权限(拦截器已限 ADMIN 才能访问 /api/admin/**,
     * 这里挂在 /api/auth/password 但只允许 ADMIN 角色调用,由方法内二次校验)。
     */
    @PostMapping("/password")
    public ResponseEntity<Void> changeOwnPassword(@RequestBody ChangePwdReq req, HttpServletRequest request) {
        User current = (User) request.getSession().getAttribute(UserService.SESSION_USER);
        if (current == null) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!current.isAdmin()) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "仅管理员可自助修改密码");
        }
        if (req.newPassword() == null || req.newPassword().length() < 6) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "新密码至少 6 位");
        }
        userService.changeOwnPassword(current, req.oldPassword(), req.newPassword());
        // 密码已变更,让旧 session 失效,强制重新登录
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }

    public static class HttpStatusException extends RuntimeException {
        private final HttpStatus status;
        public HttpStatusException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
        public HttpStatus status() { return status; }
    }
}
