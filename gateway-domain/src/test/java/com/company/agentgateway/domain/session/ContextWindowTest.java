package com.company.agentgateway.domain.session;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class ContextWindowTest {
    record FitCase(String name, List<Message> history, int budget, int expectedSize) {}

    static Stream<FitCase> fitCases() {
        return Stream.of(
            new FitCase("预算充足保留全部", messages(5), 10000, 5),
            new FitCase("超预算从最早截断", messages(5), 30, 3),  // 5*10=50>30, 移2条剩3*10=30<=30
            new FitCase("至少保留最近K=2", messages(10), 1, 2)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fitCases")
    void shouldFitWithinTokenBudget(FitCase c) {
        var w = new ContextWindow(2, m -> 10);  // minKeep=2, 每条估算10 token
        List<Message> out = w.fit(c.history(), c.budget());
        assertThat(out).hasSize(c.expectedSize);
    }

    static List<Message> messages(int n) {
        var list = new java.util.ArrayList<Message>();
        for (int i = 0; i < n; i++) list.add(new UserMessage("m" + i));
        return list;
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "minKeep={0} throws")
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1, -10})
    void rejectsInvalidMinKeep(int minKeep) {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
            .isThrownBy(() -> new ContextWindow(minKeep, m -> 10))
            .withMessageContaining("minKeep >= 1");
    }
}
