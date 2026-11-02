package com.itheima.qa.model;

import jakarta.persistence.*;

/**
 * 系统用户。角色：ADMIN（知识库/平台管理员）、USER（普通提问用户）。
 * 【讲解要点】@Entity 让 Hibernate（JPA 的实现框架）把这个类和数据库表 sys_user 映射起来：
 * 字段 → 列，对象 → 行。save(user) 就是 INSERT/UPDATE，findByUsername(...) 会翻译成 SELECT。
 */
@Entity                       // JPA 实体：一个实例对应数据库表中的一行
@Table(name = "sys_user")     // 指定表名；不写则默认类名（user 表，容易和保留字冲突所以显式命名）
public class User {

    @Id                                          // 主键标记
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 用数据库自增列（H2 的 IDENTITY）生成 id，插入前 id 为 null
    private Long id;

    @Column(unique = true, nullable = false, length = 64) // 用户名：唯一 + 非空 + varchar(64)
    private String username;

    @Column(nullable = false, length = 128) // 存储的是"加盐 SHA-256 散列"后的十六进制串（见 UserService），绝非明文
    private String password;

    @Column(nullable = false, length = 16)  // 角色字符串："ADMIN" / "USER"（本项目没用枚举，用字符串简化）
    private String role;

    public User() {}   // JPA 规范强制要求"无参构造器"：Hibernate 反射创建实例时会先调用它

    /** 便捷构造器：注册/初始化数据时一行完成三个字段赋值 */
    public User(String username, String password, String role) {
        this.username = username;   // this.x = x：形参与字段同名时，this 区分"对象的字段"
        this.password = password;
        this.role = role;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    /** 业务便捷方法：role 等于 "ADMIN" 即为管理员。"ADMIN".equals(role) 的写法
     *  保证 role 为 null 时不抛 NPE（常量在前），这是 equals 判等的防御性惯用法 */
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
