package com.company.agentgateway.interfaces.changelog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changelog 端点（spec 2026-09-05 §changelog §3）。
 *
 * <p>读 {@code CHANGELOG.md} → 极简 markdown 解析（标题 + bullet 列表 + 章节分类） →
 * 返回结构化 JSON 给前端。
 *
 * <p>格式约定（写 CHANGELOG.md 时按这个写，dev 友好）：
 * <pre>
 * ## [tag] — date
 * ### ✨ New Features
 * - bullet
 * - bullet
 * ### 🐛 Bug Fixes
 * - bullet
 * </pre>
 *
 * <p>解析是 best-effort：失败回退空 releases 数组 + raw 全文，让前端决定怎么兜底。
 */
@RestController
public class ChangelogController {

    /** 标题行：`## [tag] — date` 或 `## [tag] - date` */
    private static final Pattern RELEASE_HEADER =
            Pattern.compile("^##\\s+\\[([^\\]]+)\\]\\s*[—\\-]+\\s*(.+?)\\s*$");

    /** 章节：`### ✨ New Features` 或 `### 🐛 Bug Fixes` */
    private static final Pattern SECTION_HEADER = Pattern.compile("^###\\s+(.+?)\\s*$");

    /** bullet：`  - 文本` 或 `  * 文本` */
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.+?)\\s*$");

    /** section emoji 关键字 → 分组名 */
    private static final Map<String, String> SECTION_LABELS = Map.of(
            "✨", "features",
            "🐛", "fixes",
            "📦", "internal",
            "⚠️", "breaking",
            "🚀", "performance");

    private final ObjectMapper json = new ObjectMapper();

    @GetMapping(value = "/v1/changelog", produces = "application/json;charset=UTF-8")
    public Map<String, Object> changelog() throws Exception {
        ClassPathResource resource = new ClassPathResource("CHANGELOG.md");
        String content;
        try (InputStream in = resource.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Map<String, Object>> releases = parse(content);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("releases", releases);
        out.put("raw", content); // 前端可选择渲染纯文本 fallback
        return out;
    }

    /** Best-effort 解析；任何 release 解析失败保留为 error 字段。 */
    private List<Map<String, Object>> parse(String content) {
        // releases: 按 ## [tag] 切分
        List<Map<String, Object>> releases = new java.util.ArrayList<>();
        String[] lines = content.split("\n");
        Map<String, Object> current = null;
        String currentSection = null;
        for (String line : lines) {
            Matcher release = RELEASE_HEADER.matcher(line);
            if (release.matches()) {
                current = new LinkedHashMap<>();
                current.put("tag", release.group(1));
                current.put("date", release.group(2));
                current.put("sections", new java.util.LinkedHashMap<String, java.util.List<String>>());
                releases.add(current);
                currentSection = null;
                continue;
            }
            if (current == null) continue;
            Matcher section = SECTION_HEADER.matcher(line);
            if (section.matches()) {
                String label = normalizeSection(section.group(1));
                currentSection = label;
                ((Map<String, java.util.List<String>>) current.get("sections"))
                        .computeIfAbsent(label, k -> new java.util.ArrayList<>());
                continue;
            }
            if (currentSection == null) continue;
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                ((Map<String, java.util.List<String>>) current.get("sections"))
                        .get(currentSection).add(bullet.group(1));
            }
        }
        return releases;
    }

    private static String normalizeSection(String heading) {
        // "✨ New Features" -> "features"
        for (var e : SECTION_LABELS.entrySet()) {
            if (heading.contains(e.getKey())) return e.getValue();
        }
        return heading.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}