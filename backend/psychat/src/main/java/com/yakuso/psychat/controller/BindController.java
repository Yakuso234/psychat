package com.yakuso.psychat.controller;

import com.yakuso.psychat.common.AuthContext;
import com.yakuso.psychat.common.Result;
import com.yakuso.psychat.dto.BindRequest;
import com.yakuso.psychat.dto.BindVO;
import com.yakuso.psychat.entity.BindRelation;
import com.yakuso.psychat.entity.User;
import com.yakuso.psychat.mapper.BindRelationMapper;
import com.yakuso.psychat.mapper.UserMapper;
import com.yakuso.psychat.service.NotificationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/bind")
public class BindController {

    private final BindRelationMapper bindMapper;
    private final UserMapper userMapper;
    private final NotificationService notificationService;

    public BindController(BindRelationMapper bindMapper,
                          UserMapper userMapper,
                          NotificationService notificationService) {
        this.bindMapper = bindMapper;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
    }

    @PostMapping("/request")
    public Result<String> requestBind(@RequestBody BindRequest req) {
        Long currentUserId = AuthContext.getUserId();
        String currentRole = AuthContext.getRole();

        User targetUser = userMapper.selectByUsername(req.getTargetUsername());
        if (targetUser == null) {
            return Result.fail("目标用户不存在");
        }
        if (targetUser.getId().equals(currentUserId)) {
            return Result.fail("不能与自己建立绑定");
        }

        Long adminId, userId;
        if ("ADMIN".equals(currentRole)) {
            if (!"USER".equals(targetUser.getRole())) {
                return Result.fail("只能向普通用户发起绑定");
            }
            adminId = currentUserId;
            userId = targetUser.getId();
        } else {
            if (!"ADMIN".equals(targetUser.getRole())) {
                return Result.fail("只能向管理员发起绑定");
            }
            adminId = targetUser.getId();
            userId = currentUserId;
        }

        BindRelation existing = bindMapper.selectOne(new LambdaQueryWrapper<BindRelation>()
                .eq(BindRelation::getAdminId, adminId)
                .eq(BindRelation::getUserId, userId));
        if (existing != null) {
            if ("REJECTED".equals(existing.getStatus())) {
                existing.setStatus("PENDING");
                existing.setInitiator(currentRole);
                bindMapper.updateById(existing);
                User currentUser = userMapper.selectById(currentUserId);
                notificationService.sendBindRequest(targetUser.getId(), currentUserId, currentUser.getUsername());
                return Result.ok("绑定请求已重新发送");
            }
            return Result.fail("已存在绑定关系，请勿重复申请");
        }

        BindRelation relation = new BindRelation();
        relation.setAdminId(adminId);
        relation.setUserId(userId);
        relation.setStatus("PENDING");
        relation.setInitiator(currentRole);
        bindMapper.insert(relation);

        User currentUser = userMapper.selectById(currentUserId);
        notificationService.sendBindRequest(targetUser.getId(), currentUserId, currentUser.getUsername());

        return Result.ok("绑定请求已发送");
    }

    @PostMapping("/respond")
    public Result<String> respond(@RequestParam Long bindId, @RequestParam String action) {
        Long currentUserId = AuthContext.getUserId();
        String currentRole = AuthContext.getRole();

        BindRelation relation = bindMapper.selectById(bindId);
        if (relation == null) {
            return Result.fail("绑定请求不存在");
        }

        if (!"PENDING".equals(relation.getStatus())) {
            return Result.fail("该请求已处理");
        }

        // 只有被请求方才能同意或拒绝
        boolean isInitiator = currentRole.equals(relation.getInitiator())
                && (currentUserId.equals(relation.getAdminId()) || currentUserId.equals(relation.getUserId()));
        if (isInitiator) {
            return Result.fail("不能处理自己发起的请求，请等待对方回应");
        }

        boolean isReceiver = currentUserId.equals(relation.getUserId())
                || currentUserId.equals(relation.getAdminId());
        if (!isReceiver) {
            return Result.fail("无权操作");
        }

        if ("accept".equals(action)) {
            relation.setStatus("ACCEPTED");
        } else if ("reject".equals(action)) {
            relation.setStatus("REJECTED");
        } else {
            return Result.fail("无效的操作");
        }

        bindMapper.updateById(relation);

        // 通知发起方结果
        Long notifierId = currentRole.equals("ADMIN") ? relation.getUserId() : relation.getAdminId();
        User currentUser = userMapper.selectById(currentUserId);
        if ("accept".equals(action)) {
            notificationService.sendToUser(notifierId,
                    "{\"type\":\"BIND_ACCEPTED\",\"message\":\"" + currentUser.getUsername() + " 已接受你的绑定请求\"}");
        }

        return Result.ok("ACCEPTED".equals(relation.getStatus()) ? "已接受" : "已拒绝");
    }

    @PostMapping("/cancel")
    public Result<String> cancel(@RequestParam Long bindId) {
        Long currentUserId = AuthContext.getUserId();

        BindRelation relation = bindMapper.selectById(bindId);
        if (relation == null) {
            return Result.fail("绑定关系不存在");
        }

        if (!"ACCEPTED".equals(relation.getStatus())) {
            return Result.fail("只能取消已建立的绑定");
        }

        boolean isParty = relation.getUserId().equals(currentUserId)
                || relation.getAdminId().equals(currentUserId);
        if (!isParty) {
            return Result.fail("无权操作");
        }

        bindMapper.deleteById(bindId);

        // 通知对方
        Long otherId = relation.getUserId().equals(currentUserId)
                ? relation.getAdminId() : relation.getUserId();
        User currentUser = userMapper.selectById(currentUserId);
        notificationService.sendToUser(otherId,
                "{\"type\":\"BIND_CANCELLED\",\"message\":\"" + currentUser.getUsername() + " 已解除与你的绑定\"}");

        return Result.ok("已解除绑定");
    }

    @GetMapping("/list")
    public Result<List<BindVO>> listBinds() {
        Long userId = AuthContext.getUserId();
        String role = AuthContext.getRole();

        List<BindRelation> list;
        if ("ADMIN".equals(role)) {
            list = bindMapper.selectList(new LambdaQueryWrapper<BindRelation>()
                    .eq(BindRelation::getAdminId, userId));
        } else {
            list = bindMapper.selectList(new LambdaQueryWrapper<BindRelation>()
                    .eq(BindRelation::getUserId, userId));
        }

        List<BindVO> vos = new ArrayList<>();
        for (BindRelation r : list) {
            User admin = userMapper.selectById(r.getAdminId());
            User user = userMapper.selectById(r.getUserId());
            vos.add(new BindVO(r, admin, user));
        }
        return Result.ok(vos);
    }
}
