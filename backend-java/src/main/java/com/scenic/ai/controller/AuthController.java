package com.scenic.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scenic.ai.dto.Result;
import com.scenic.ai.entity.User;
import com.scenic.ai.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserMapper userMapper;

    // 内存 token 存储：token -> userId
    private static final Map<String, Long> tokenStore = new ConcurrentHashMap<>();

    public static Long getUserIdFromToken(String token) {
        if (token == null || token.isEmpty()) return null;
        return tokenStore.get(token);
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String nickname = body.get("nickname");

        if (username == null || username.isBlank()) {
            return Result.error(400, "用户名不能为空");
        }
        if (password == null || password.length() < 4) {
            return Result.error(400, "密码至少4位");
        }

        // 检查重复
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (exists > 0) {
            return Result.error(400, "用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(password); // 简单存储，比赛项目不加密
        user.setNickname(nickname != null ? nickname : username);
        user.setRole("visitor");
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        // 自动登录
        String token = UUID.randomUUID().toString();
        tokenStore.put(token, user.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", toUserInfo(user));
        return Result.success(data);
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            return Result.error(400, "用户名和密码不能为空");
        }

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
                        .eq(User::getPassword, password));

        if (user == null) {
            return Result.error(401, "用户名或密码错误");
        }

        String token = UUID.randomUUID().toString();
        tokenStore.put(token, user.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", toUserInfo(user));
        return Result.success(data);
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = extractToken(auth);
        Long userId = getUserIdFromToken(token);
        if (userId == null) {
            return Result.error(401, "未登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }

        return Result.success(toUserInfo(user));
    }

    private String extractToken(String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
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
