package com.scenic.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.dto.Result;
import com.scenic.ai.entity.User;
import com.scenic.ai.mapper.UserMapper;
import com.scenic.ai.security.AuthUser;
import com.scenic.ai.security.CurrentUser;
import com.scenic.ai.security.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wave 0 · 身份认证。
 *
 * 相对旧实现的三处升级（对应路线图 B10 / B5）：
 *   1. **token 从「UUID 存内存 Map」换成 JWT** —— 无状态、可水平扩容、携带角色、有明确过期时间；
 *      旧方案重启即全员掉线，且多实例部署必然不一致。
 *   2. **密码从明文换成 BCrypt** —— 并对历史明文账号做**渐进式升级**：登录成功时自动改写为哈希，
 *      不打断存量账号。
 *   3. **新增游客凭证签发** —— visitorId 改由服务端生成并签名，客户端无法再伪造他人身份
 *      读取其跨会话记忆（原实现里 visitorId 完全由前端声明）。
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String nickname = body.get("nickname");

        if (username == null || username.isBlank()) {
            return Result.error(400, "用户名不能为空");
        }
        if (username.length() > 32) {
            return Result.error(400, "用户名最长 32 个字符");
        }
        if (password == null || password.length() < 6) {
            return Result.error(400, "密码至少 6 位");
        }
        if (password.length() > 64) {
            return Result.error(400, "密码最长 64 位");
        }

        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (exists > 0) {
            return Result.error(400, "用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setRole("visitor");
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        return Result.success(tokenPayload(user));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            return Result.error(400, "用户名和密码不能为空");
        }

        // 注意：不能再把密码写进查询条件（旧实现 .eq(User::getPassword, password) 是明文比对）
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));

        if (user == null || !passwordMatchesAndMaybeUpgrade(password, user)) {
            // 不区分「用户不存在」与「密码错误」，避免账号枚举
            return Result.error(401, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Result.error(403, "账号已被禁用");
        }

        return Result.success(tokenPayload(user));
    }

    /** 当前登录用户信息（身份由 JWT 过滤链注入，不再手工解析请求头） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        AuthUser current = CurrentUser.get();
        if (current == null) {
            return Result.error(401, "未登录");
        }
        User user = userMapper.selectById(current.id());
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(toUserInfo(user));
    }

    /**
     * 签发游客凭证。
     *
     * **visitorId 由服务端生成**，不接受客户端声明 —— 这是防越权的关键：
     * 若允许客户端自带 id，攻击者声明别人的 id 即可读到其跨会话长期记忆。
     * 前端把返回的 visitorToken 存本地，之后随对话请求携带。
     */
    @PostMapping("/guest")
    public Result<Map<String, Object>> guest() {
        String visitorId = "guest-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String visitorToken = jwtService.issueGuestToken(visitorId);

        Map<String, Object> data = new HashMap<>();
        data.put("visitorId", visitorId);
        data.put("visitorToken", visitorToken);
        log.info("[AUTH] 已签发游客凭证：{}", visitorId);
        return Result.success(data);
    }

    /**
     * 校验密码，并对历史明文密码做渐进式升级。
     * 迁移期结束后（存量账号全部登录过一次）可删除明文分支。
     */
    private boolean passwordMatchesAndMaybeUpgrade(String rawPassword, User user) {
        String stored = user.getPassword();
        if (stored == null || stored.isBlank()) {
            return false;
        }

        if (isBcryptHash(stored)) {
            return passwordEncoder.matches(rawPassword, stored);
        }

        // 历史明文密码：比对成功后立刻改写为 BCrypt
        if (stored.equals(rawPassword)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);
            log.info("[SECURITY] 检测到明文历史密码，已自动升级为 BCrypt：userId={}", user.getId());
            return true;
        }
        return false;
    }

    private boolean isBcryptHash(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }

    private Map<String, Object> tokenPayload(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", jwtService.issueUserToken(user.getId(), user.getUsername(), user.getRole()));
        data.put("user", toUserInfo(user));
        return data;
    }

    private Map<String, Object> toUserInfo(User user) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", user.getId());
        info.put("username", user.getUsername());
        info.put("nickname", user.getNickname());
        info.put("role", user.getRole());
        return info;
    }
}
