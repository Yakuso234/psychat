package com.yakuso.psychat.service;

import com.yakuso.psychat.common.JwtUtil;
import com.yakuso.psychat.dto.LoginRequest;
import com.yakuso.psychat.dto.LoginResponse;
import com.yakuso.psychat.dto.RegisterRequest;
import com.yakuso.psychat.entity.User;
import com.yakuso.psychat.entity.UserPreference;
import com.yakuso.psychat.mapper.UserMapper;
import com.yakuso.psychat.mapper.UserPreferenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final UserPreferenceMapper preferenceMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper,
                       UserPreferenceMapper preferenceMapper,
                       JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.preferenceMapper = preferenceMapper;
        this.jwtUtil = jwtUtil;
    }

    public LoginResponse register(RegisterRequest req) {
        User existing = userMapper.selectByUsername(req.getUsername());
        if (existing != null) {
            throw new RuntimeException("用户名已存在");
        }

        String role = "ADMIN".equalsIgnoreCase(req.getRole()) ? "ADMIN" : "USER";

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getUsername());
        user.setRole(role);
        userMapper.insert(user);

        // create default preferences
        UserPreference pref = new UserPreference();
        pref.setUserId(user.getId());
        pref.setToneStyle("warm");
        pref.setResponseLength("medium");
        pref.setAllowProactive(false);
        preferenceMapper.insert(pref);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getRole());
    }

    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectByUsername(req.getUsername());
        if (user == null) {
            throw new RuntimeException("用户名或密码错误");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new RuntimeException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getRole());
    }
}
