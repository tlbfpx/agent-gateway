package com.company.agentgateway.interfaces.changelog;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChangelogController 解析测试（spec §changelog §3）。
 *
 * <p>解析是 best-effort：用反射调 private parse() 验证关键 pattern；
 * endpoint 本身用 MockMvc 集成验证更稳，本轮先覆盖核心逻辑。
 */
class ChangelogControllerTest {

    @Test
    void parsesStructuredMarkdown() throws Exception {
        String md = """
                # Changelog

                ## [Unreleased] — 2026-09-05

                ### ✨ New Features
                - Demo 模式
                - 自助注册
                ### 🐛 Bug Fixes
                - 路径双前缀
                """;

        ChangelogController c = new ChangelogController();
        Method m = ChangelogController.class.getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> releases = (List<Map<String, Object>>) m.invoke(c, md);

        assertThat(releases).hasSize(1);
        Map<String, Object> rel = releases.get(0);
        assertThat(rel.get("tag")).isEqualTo("Unreleased");
        assertThat(rel.get("date")).isEqualTo("2026-09-05");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> sections = (Map<String, List<String>>) rel.get("sections");
        assertThat(sections).containsKeys("features", "fixes");
        assertThat(sections.get("features")).containsExactly("Demo 模式", "自助注册");
        assertThat(sections.get("fixes")).containsExactly("路径双前缀");
    }

    @Test
    void parsesMultipleReleases() throws Exception {
        String md = """
                ## [0.2.0] — 2026-09-01
                ### ✨ New Features
                - A

                ## [0.1.0] — 2026-08-15
                ### 🐛 Bug Fixes
                - B
                """;

        ChangelogController c = new ChangelogController();
        Method m = ChangelogController.class.getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> releases = (List<Map<String, Object>>) m.invoke(c, md);

        assertThat(releases).hasSize(2);
        assertThat(releases.get(0).get("tag")).isEqualTo("0.2.0");
        assertThat(releases.get(1).get("tag")).isEqualTo("0.1.0");
    }

    @Test
    void emptyInputYieldsNoReleases() throws Exception {
        ChangelogController c = new ChangelogController();
        Method m = ChangelogController.class.getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> releases = (List<Map<String, Object>>) m.invoke(c, "# Header only\n");
        assertThat(releases).isEmpty();
    }

    @Test
    void normalizesEmojiSectionNames() throws Exception {
        String md = """
                ## [t] — d
                ### ⚠️ Breaking
                - x
                ### 🚀 Performance
                - y
                ### unknown section
                - z
                """;

        ChangelogController c = new ChangelogController();
        Method m = ChangelogController.class.getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> releases = (List<Map<String, Object>>) m.invoke(c, md);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> sections =
                (Map<String, List<String>>) releases.get(0).get("sections");
        assertThat(sections).containsKeys("breaking", "performance", "unknown_section");
    }

    @Test
    void bulletsWithStarMarkerWork() throws Exception {
        String md = """
                ## [t] — d
                ### ✨ New Features
                * bullet with star
                """;

        ChangelogController c = new ChangelogController();
        Method m = ChangelogController.class.getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> releases = (List<Map<String, Object>>) m.invoke(c, md);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> sections =
                (Map<String, List<String>>) releases.get(0).get("sections");
        assertThat(sections.get("features")).containsExactly("bullet with star");
    }
}