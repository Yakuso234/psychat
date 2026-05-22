package com.yakuso.psychat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yakuso.psychat.entity.UserPreference;
import com.yakuso.psychat.mapper.UserPreferenceMapper;
import org.springframework.stereotype.Service;

@Service
public class UserPreferenceService {

    private final UserPreferenceMapper preferenceMapper;

    public UserPreferenceService(UserPreferenceMapper preferenceMapper) {
        this.preferenceMapper = preferenceMapper;
    }

    public UserPreference getByUserId(Long userId) {
        return preferenceMapper.selectOne(
                new LambdaQueryWrapper<UserPreference>()
                        .eq(UserPreference::getUserId, userId));
    }

    public void saveOrUpdate(Long userId, String toneStyle, String responseLength, Boolean allowProactive) {
        UserPreference existing = getByUserId(userId);
        if (existing != null) {
            existing.setToneStyle(toneStyle);
            existing.setResponseLength(responseLength);
            existing.setAllowProactive(allowProactive);
            preferenceMapper.updateById(existing);
        } else {
            UserPreference pref = new UserPreference();
            pref.setUserId(userId);
            pref.setToneStyle(toneStyle);
            pref.setResponseLength(responseLength);
            pref.setAllowProactive(allowProactive);
            preferenceMapper.insert(pref);
        }
    }
}
