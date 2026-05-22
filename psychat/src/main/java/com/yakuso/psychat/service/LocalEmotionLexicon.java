package com.yakuso.psychat.service;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Zero-latency local keyword matcher for crisis detection and emotion tagging.
 * Runs before any API call — if crisis keywords hit, the entire retrieval
 * strategy shifts to L0 crisis protocol immediately.
 */
@Component
public class LocalEmotionLexicon {

    // ── L0 Crisis: any single match → crisis mode, skip normal flow ──
    private static final Set<String> CRISIS_KEYWORDS = Set.of(
            "不想活了", "想死", "不想活", "自杀", "结束生命", "结束这一切",
            "活够了", "活不下去了", "活着好累", "活着没意思", "活着没意义",
            "没意义了", "没有意义", "不想继续了", "撑不下去了", "坚持不住了",
            "我真没用", "我死了算了", "一了百了", "想解脱", "生无可恋",
            "活着干什么", "不如死了", "死了算了", "想去死", "想消失",
            "不想存在", "活着太累了", "我撑不住了", "真的受不了了", "熬不下去了"
    );

    // ── Emotion keyword → label mapping (greedy longest-match first) ──
    // Categories (20 negative): 焦虑 低落 疲惫 烦躁 委屈 悲伤 绝望 困惑 孤独
    //                           内耗 愤怒 压力 恐慌 恐惧 后悔 无力 麻木 失望 压抑 被抛弃
    private static final LinkedHashMap<String, String> EMOTION_MAP = new LinkedHashMap<>();
    static {
        // ── 低落 / 疲惫 ──
        EMOTION_MAP.put("提不起精神", "低落");
        EMOTION_MAP.put("什么都不想做", "低落");
        EMOTION_MAP.put("不想说话", "低落");
        EMOTION_MAP.put("不想出门", "低落");
        EMOTION_MAP.put("不想动", "低落");
        EMOTION_MAP.put("心力交瘁", "疲惫");
        EMOTION_MAP.put("好累", "疲惫");
        EMOTION_MAP.put("心累", "疲惫");
        EMOTION_MAP.put("疲惫", "疲惫");

        // ── 焦虑 ──
        EMOTION_MAP.put("喘不过气", "焦虑");
        EMOTION_MAP.put("睡不着", "焦虑");
        EMOTION_MAP.put("失眠", "焦虑");
        EMOTION_MAP.put("焦虑", "焦虑");
        EMOTION_MAP.put("紧张", "焦虑");
        EMOTION_MAP.put("不安", "焦虑");
        EMOTION_MAP.put("心慌", "焦虑");

        // ── 烦躁 ──
        EMOTION_MAP.put("好烦", "烦躁");
        EMOTION_MAP.put("烦死了", "烦躁");
        EMOTION_MAP.put("真烦", "烦躁");

        // ── 委屈 ──
        EMOTION_MAP.put("委屈", "委屈");

        // ── 悲伤 ──
        EMOTION_MAP.put("难过", "悲伤");
        EMOTION_MAP.put("想哭", "悲伤");
        EMOTION_MAP.put("伤心", "悲伤");

        // ── 绝望 ──
        EMOTION_MAP.put("崩溃", "绝望");
        EMOTION_MAP.put("绝望", "绝望");

        // ── 困惑 ──
        EMOTION_MAP.put("不知道怎么办", "困惑");
        EMOTION_MAP.put("迷茫", "困惑");

        // ── 孤独 ──
        EMOTION_MAP.put("没人在乎", "孤独");
        EMOTION_MAP.put("没人理解", "孤独");
        EMOTION_MAP.put("好孤单", "孤独");
        EMOTION_MAP.put("一个人", "孤独");

        // ── 内耗 ──
        EMOTION_MAP.put("觉得自己不好", "内耗");
        EMOTION_MAP.put("自我怀疑", "内耗");
        EMOTION_MAP.put("讨厌自己", "内耗");
        EMOTION_MAP.put("不如别人", "内耗");
        EMOTION_MAP.put("做不好", "内耗");
        EMOTION_MAP.put("不够好", "内耗");
        EMOTION_MAP.put("好没用", "内耗");
        EMOTION_MAP.put("真没用", "内耗");
        EMOTION_MAP.put("太差了", "内耗");
        EMOTION_MAP.put("没价值", "内耗");
        EMOTION_MAP.put("我不配", "内耗");
        EMOTION_MAP.put("比不上", "内耗");
        EMOTION_MAP.put("纠结", "内耗");
        EMOTION_MAP.put("内耗", "内耗");
        EMOTION_MAP.put("失败", "内耗");
        EMOTION_MAP.put("没用", "内耗");

        // ── 愤怒 ──
        EMOTION_MAP.put("不公平", "愤怒");
        EMOTION_MAP.put("愤怒", "愤怒");
        EMOTION_MAP.put("生气", "愤怒");

        // ── 压力 ──
        EMOTION_MAP.put("压力好大", "压力");
        EMOTION_MAP.put("压力", "压力");

        // ── 恐慌 ──
        EMOTION_MAP.put("喘不上气", "恐慌");
        EMOTION_MAP.put("快要死了", "恐慌");
        EMOTION_MAP.put("控制不住自己", "恐慌");
        EMOTION_MAP.put("恐慌", "恐慌");

        // ── 恐惧 ──
        EMOTION_MAP.put("不敢面对", "恐惧");
        EMOTION_MAP.put("好害怕", "恐惧");
        EMOTION_MAP.put("吓死我了", "恐惧");
        EMOTION_MAP.put("太可怕了", "恐惧");
        EMOTION_MAP.put("好怕", "恐惧");
        EMOTION_MAP.put("害怕", "恐惧");
        EMOTION_MAP.put("恐惧", "恐惧");
        EMOTION_MAP.put("吓到了", "恐惧");

        // ── 后悔 ──
        EMOTION_MAP.put("要是当初", "后悔");
        EMOTION_MAP.put("早知道就", "后悔");
        EMOTION_MAP.put("我对不起", "后悔");
        EMOTION_MAP.put("都怪我", "后悔");
        EMOTION_MAP.put("是我的错", "后悔");
        EMOTION_MAP.put("我做错了", "后悔");
        EMOTION_MAP.put("怪自己", "后悔");
        EMOTION_MAP.put("后悔", "后悔");
        EMOTION_MAP.put("自责", "后悔");

        // ── 无力 ──
        EMOTION_MAP.put("谁也帮不了我", "无力");
        EMOTION_MAP.put("无能为力", "无力");
        EMOTION_MAP.put("身不由己", "无力");
        EMOTION_MAP.put("改变不了", "无力");
        EMOTION_MAP.put("没有办法", "无力");
        EMOTION_MAP.put("帮不了", "无力");
        EMOTION_MAP.put("无助", "无力");

        // ── 麻木 ──
        EMOTION_MAP.put("什么都感受不到", "麻木");
        EMOTION_MAP.put("行尸走肉", "麻木");
        EMOTION_MAP.put("没什么感觉", "麻木");
        EMOTION_MAP.put("一片空白", "麻木");
        EMOTION_MAP.put("没感觉了", "麻木");
        EMOTION_MAP.put("空空的", "麻木");
        EMOTION_MAP.put("空洞", "麻木");
        EMOTION_MAP.put("麻木", "麻木");

        // ── 失望 ──
        EMOTION_MAP.put("太失望了", "失望");
        EMOTION_MAP.put("指望不上", "失望");
        EMOTION_MAP.put("心凉了", "失望");
        EMOTION_MAP.put("寒心", "失望");
        EMOTION_MAP.put("失望", "失望");

        // ── 压抑 ──
        EMOTION_MAP.put("闷在心里", "压抑");
        EMOTION_MAP.put("透不过气", "压抑");
        EMOTION_MAP.put("说不出口", "压抑");
        EMOTION_MAP.put("堵得慌", "压抑");
        EMOTION_MAP.put("一直憋着", "压抑");
        EMOTION_MAP.put("压抑", "压抑");
        EMOTION_MAP.put("憋着", "压抑");
        EMOTION_MAP.put("忍着", "压抑");

        // ── 被抛弃 ──
        EMOTION_MAP.put("没有人要我", "被抛弃");
        EMOTION_MAP.put("不要我了", "被抛弃");
        EMOTION_MAP.put("被抛弃了", "被抛弃");
        EMOTION_MAP.put("离开我了", "被抛弃");
        EMOTION_MAP.put("被甩了", "被抛弃");
        EMOTION_MAP.put("抛弃", "被抛弃");
        EMOTION_MAP.put("不要我", "被抛弃");
    }

    public record LexiconResult(boolean isCrisis, List<String> emotionTags) {}

    public LexiconResult analyze(String text) {
        if (text == null || text.isBlank()) {
            return new LexiconResult(false, List.of());
        }

        // crisis check first
        for (String kw : CRISIS_KEYWORDS) {
            if (text.contains(kw)) {
                return new LexiconResult(true, List.of("绝望"));
            }
        }

        // emotion keywords (greedy: first match wins per position)
        Set<String> tags = new LinkedHashSet<>();
        String lower = text.toLowerCase();
        for (var entry : EMOTION_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) {
                tags.add(entry.getValue());
                if (tags.size() >= 3) break;
            }
        }

        return new LexiconResult(false, new ArrayList<>(tags));
    }
}
