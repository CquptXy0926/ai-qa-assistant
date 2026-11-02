package com.itheima.qa.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中英文混合分词：
 * - 英文/数字：按单词，小写归一；
 * - 中文：取连续 1-gram + 2-gram（比引入分词器词典更轻量，检索效果对 QA 场景足够）。
 */
public final class Tokenizer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9_+.\\-]+|[\\u4e00-\\u9fa5]+");

    private Tokenizer() {}

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher m = TOKEN_PATTERN.matcher(text);
        while (m.find()) {
            String seg = m.group();
            if (Character.isDigit(seg.charAt(0)) || (seg.charAt(0) >= 'a' && seg.charAt(0) <= 'z')
                    || (seg.charAt(0) >= 'A' && seg.charAt(0) <= 'Z')) {
                tokens.add(seg.toLowerCase(Locale.ROOT));
            } else {
                // 中文片段：单字 + 相邻双字
                for (int i = 0; i < seg.length(); i++) {
                    tokens.add(seg.substring(i, i + 1));
                    if (i + 2 <= seg.length()) {
                        tokens.add(seg.substring(i, i + 2));
                    }
                }
            }
        }
        return tokens;
    }
}
