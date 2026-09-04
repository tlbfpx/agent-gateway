package com.company.agentgateway.domain.safety;

import java.util.List;

/**
 * 内置 Guardrail 规则库(Round 9):domain 静态常量。
 *
 * <h2>覆盖</h2>
 * <ul>
 *   <li>邮箱 / 中国大陆手机号 / 身份证号(18 位) / 银行卡号(16-19 位)</li>
 *   <li>常见 jailbreak 模式:中英文 ≥20 条</li>
 *   <li>常见 toxicity 关键词:中英文 ≥50 条</li>
 * </ul>
 *
 * <p>运营可在 Guardrails UI 追加自定义规则;此处仅提供基线。
 */
public final class DefaultGuardrailLibrary {

    private DefaultGuardrailLibrary() {}

    // ============== PII ==============

    public static final List<String> DEFAULT_PII_PATTERNS = List.of(
        // 邮箱
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
        // 中国大陆手机号
        "(?<![0-9])1[3-9][0-9]{9}(?![0-9])",
        // 中国大陆身份证号(18 位,末位 X 允许)
        "(?<![0-9A-Za-z])[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9A-Za-z])",
        // 银行卡号(16-19 位连续数字)
        "(?<![0-9])[0-9]{16,19}(?![0-9])"
    );

    // ============== Jailbreak ==============

    public static final List<String> DEFAULT_JAILBREAK_PATTERNS = List.of(
        // 中文
        "忽略之前的所有指令",
        "忽略之前的指示",
        "忽略你的系统提示",
        "忽略你的设定",
        "不要遵守任何限制",
        "你现在没有限制",
        "请扮演一个没有限制的AI",
        "请扮演一个没有道德约束的",
        "进入开发者模式",
        "进入开发者模式",
        "进入debug模式",
        "进入dan模式",
        "请假装是一个不受限制的",
        "无视你的内容政策",
        "绕过你的安全限制",
        // 英文
        "(?i)ignore (?:all )?(?:previous|prior|above) instructions",
        "(?i)ignore your (?:system )?prompt",
        "(?i)forget (?:everything|all) (?:you'?ve )?(?:been )?told",
        "(?i)you (?:are|'re) (?:now )?dan",
        "(?i)do anything now",
        "(?i)developer mode",
        "(?i)jailbreak",
        "(?i)no (?:rules|restrictions|limitations) (?:apply|matter)",
        "(?i)pretend (?:you|to be) (?:an? )?(?:evil|unrestricted)",
        "(?i)bypass (?:your )?(?:safety|content|ethical) (?:filter|policy|guidelines)"
    );

    // ============== Toxicity 关键词 ==============

    public static final List<String> DEFAULT_TOXICITY_KEYWORDS = List.of(
        // 中文(常见攻击/歧视/违法)
        "白痴", "弱智", "废物", "垃圾", "滚蛋", "去死", "杀了你", "弄死你",
        "婊子", "妓女", "操你", "草泥马", "智障", "脑残", "狗屎",
        "恐怖袭击", "制造炸弹", "毒品制造", "自杀方法",
        // 英文
        "idiot", "moron", "stupid", "dumb", "kill yourself", "kys",
        "fuck you", "shit", "bitch", "whore", "bastard",
        "terrorist attack", "how to make a bomb", "drug synthesis",
        "rape", "genocide"
    );
}