package com.yakuso.psychat.controller;

import com.yakuso.psychat.common.AuthContext;
import com.yakuso.psychat.common.Result;
import com.yakuso.psychat.entity.UserPreference;
import com.yakuso.psychat.service.UserPreferenceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/preference")
public class PreferenceController {

    private final UserPreferenceService preferenceService;

    public PreferenceController(UserPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    public Result<UserPreference> get() {
        Long userId = AuthContext.getUserId();
        UserPreference pref = preferenceService.getByUserId(userId);
        if (pref == null) {
            UserPreference defaultPref = new UserPreference();
            defaultPref.setUserId(userId);
            defaultPref.setToneStyle("warm");
            defaultPref.setResponseLength("medium");
            defaultPref.setAllowProactive(false);
            return Result.ok(defaultPref);
        }
        return Result.ok(pref);
    }

    @PutMapping
    public Result<String> update(@RequestBody Map<String, Object> body) {
        Long userId = AuthContext.getUserId();
        String tone = body.getOrDefault("toneStyle", "warm").toString();
        String length = body.getOrDefault("responseLength", "medium").toString();
        Boolean proactive = (Boolean) body.getOrDefault("allowProactive", false);
        preferenceService.saveOrUpdate(userId, tone, length, proactive);
        return Result.ok("已更新");
    }
}
