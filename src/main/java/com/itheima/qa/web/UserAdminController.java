package com.itheima.qa.web;

import com.itheima.qa.model.User;
import com.itheima.qa.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员用户管理接口(仅 ADMIN 可访问,由 AuthInterceptor 通过 /api/admin/** 前缀统一拦截)。
 * 支持:列出所有账号、创建普通账号、删除账号、重置任意账号密码。
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    /** 创建账号请求体:仅允许创建普通用户,角色固定为 USER。 */
    public record CreateReq(@NotBlank String username,
                            @NotBlank @Size(min = 6, message = "密码至少 6 位") String password) {}

    /** 重置密码请求体。 */
    public record ResetPwdReq(@NotBlank @Size(min = 6, message = "密码至少 6 位") String newPassword) {}

    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return userService.listAll().stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(),
                        "username", u.getUsername(),
                        "role", u.getRole()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody @Valid CreateReq req) {
        User u = userService.register(req.username().trim(), req.password(), "USER");
        return Map.of("id", u.getId(), "username", u.getUsername(), "role", u.getRole());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, HttpServletRequest request) {
        User current = (User) request.getSession().getAttribute(UserService.SESSION_USER);
        userService.adminDeleteUser(current == null ? null : current.getId(), id);
    }

    /** 管理员重置任意用户密码(包括其他管理员/自己)。 */
    @PostMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @RequestBody @Valid ResetPwdReq req) {
        userService.adminResetPassword(id, req.newPassword());
        return ResponseEntity.noContent().build();
    }
}
