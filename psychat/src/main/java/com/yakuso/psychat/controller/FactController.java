package com.yakuso.psychat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yakuso.psychat.common.AuthContext;
import com.yakuso.psychat.common.Result;
import com.yakuso.psychat.entity.UserFact;
import com.yakuso.psychat.mapper.UserFactMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/fact")
public class FactController {

    private final UserFactMapper factMapper;

    public FactController(UserFactMapper factMapper) {
        this.factMapper = factMapper;
    }

    @GetMapping("/list")
    public Result<List<UserFact>> list() {
        Long userId = AuthContext.getUserId();
        List<UserFact> facts = factMapper.selectList(
                new LambdaQueryWrapper<UserFact>()
                        .eq(UserFact::getUserId, userId)
                        .orderByAsc(UserFact::getCategory)
                        .orderByDesc(UserFact::getCreatedAt)
        );
        return Result.ok(facts);
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        Long userId = AuthContext.getUserId();
        UserFact fact = factMapper.selectById(id);
        if (fact == null || !fact.getUserId().equals(userId)) {
            return Result.fail(403, "无权操作");
        }
        factMapper.deleteById(id);
        return Result.ok("已删除");
    }

    @DeleteMapping("/clear")
    public Result<String> clear() {
        Long userId = AuthContext.getUserId();
        factMapper.delete(new LambdaQueryWrapper<UserFact>()
                .eq(UserFact::getUserId, userId));
        return Result.ok("已清空所有结构化记忆");
    }
}
