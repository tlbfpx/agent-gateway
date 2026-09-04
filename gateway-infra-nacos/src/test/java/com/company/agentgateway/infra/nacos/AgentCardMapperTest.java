package com.company.agentgateway.infra.nacos;

import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCardMapperTest {

    @Test
    void null入参返回null() {
        assertThat(AgentCardMapper.toDomain(null)).isNull();
    }

    @Test
    void 完整映射_含skills与endpointUrl() {
        AgentCardDetailInfo nacos = new AgentCardDetailInfo();
        nacos.setName("hr-agent");
        nacos.setDescription("请假助手");
        nacos.setVersion("1.2.0");
        nacos.setUrl("https://hr.example.com/a2a");
        AgentSkill s1 = new AgentSkill();
        s1.setName("请假");
        AgentSkill s2 = new AgentSkill();
        s2.setName("假期查询");
        nacos.setSkills(List.of(s1, s2));

        var domain = AgentCardMapper.toDomain(nacos);

        assertThat(domain).isNotNull();
        assertThat(domain.name()).isEqualTo("hr-agent");
        assertThat(domain.description()).isEqualTo("请假助手");
        assertThat(domain.version()).isEqualTo("1.2.0");
        assertThat(domain.skills()).containsExactly("请假", "假期查询");
        assertThat(domain.endpointUrl()).isEqualTo("https://hr.example.com/a2a");
        assertThat(domain.available()).isTrue();
        // Nacos A2A 无 schema，domain 填空
        assertThat(domain.inputSchema()).isEqualTo("{}");
        assertThat(domain.outputSchema()).isEqualTo("{}");
    }

    @Test
    void 无skills和url时_映射为空列表和null() {
        AgentCardDetailInfo nacos = new AgentCardDetailInfo();
        nacos.setName("orphan");
        nacos.setVersion("0.0.1");

        var domain = AgentCardMapper.toDomain(nacos);

        assertThat(domain.skills()).isEmpty();
        assertThat(domain.endpointUrl()).isNull();
    }

    @Test
    void skill的null名字被过滤() {
        AgentCardDetailInfo nacos = new AgentCardDetailInfo();
        nacos.setName("x");
        AgentSkill valid = new AgentSkill();
        valid.setName("ok");
        AgentSkill nullName = new AgentSkill(); // name=null
        nacos.setSkills(List.of(valid, nullName));

        var domain = AgentCardMapper.toDomain(nacos);

        assertThat(domain.skills()).containsExactly("ok");
    }
}
