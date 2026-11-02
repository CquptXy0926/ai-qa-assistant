package com.itheima.qa.service;

import com.itheima.qa.model.User;
import com.itheima.qa.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 用户与登录:SHA-256 加盐散列存储密码,登录态放 HttpSession。
 * 提供管理员侧的用户增删改查(创建普通账号/重置密码/删除)与自助改密能力。
 */
@Service
public class UserService {

    public static final String SESSION_USER = "LOGIN_USER";

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User login(String username, String password) {
        return userRepository.findByUsername(username)
                .filter(u -> u.getPassword().equals(hash(username, password)))
                .orElse(null);
    }

    /** 内部使用:初始化种子数据、管理员新建账号都走这里。 */
    public User register(String username, String password, String role) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }
        return userRepository.save(new User(username, hash(username, password), role));
    }

    public boolean hasAnyUser() {
        return userRepository.count() > 0;
    }

    public List<User> listAll() {
        return userRepository.findAll();
    }

    /** 管理员重置指定用户密码(不需要旧密码)。 */
    public void adminResetPassword(Long userId, String newPassword) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        u.setPassword(hash(u.getUsername(), newPassword));
        userRepository.save(u);
    }

    /** 管理员删除指定用户。禁止删除自己;禁止删除最后一个 ADMIN。 */
    public void adminDeleteUser(Long operatorId, Long targetId) {
        if (operatorId != null && operatorId.equals(targetId)) {
            throw new IllegalArgumentException("不能删除当前登录的账号");
        }
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (target.isAdmin()) {
            long adminCount = userRepository.findAll().stream().filter(User::isAdmin).count();
            if (adminCount <= 1) {
                throw new IllegalArgumentException("系统必须保留至少一个管理员账号");
            }
        }
        userRepository.deleteById(targetId);
    }

    /** 自助改密:校验旧密码后写入新密码。 */
    public void changeOwnPassword(User current, String oldPassword, String newPassword) {
        if (current.getPassword() == null
                || !current.getPassword().equals(hash(current.getUsername(), oldPassword == null ? "" : oldPassword))) {
            throw new IllegalArgumentException("旧密码不正确");
        }
        current.setPassword(hash(current.getUsername(), newPassword));
        userRepository.save(current);
    }

    static String hash(String salt, String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((salt + ":" + raw).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
