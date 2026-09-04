package com.company.agentgateway.domain.feedback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackSentimentTest {

    @Test
    void parse_aliasedForms() {
        assertEquals(FeedbackSentiment.POSITIVE, FeedbackSentiment.parse("thumbs_up"));
        assertEquals(FeedbackSentiment.POSITIVE, FeedbackSentiment.parse("👍"));
        assertEquals(FeedbackSentiment.POSITIVE, FeedbackSentiment.parse("good"));
        assertEquals(FeedbackSentiment.NEGATIVE, FeedbackSentiment.parse("thumbs_down"));
        assertEquals(FeedbackSentiment.NEGATIVE, FeedbackSentiment.parse("👎"));
        assertEquals(FeedbackSentiment.NEGATIVE, FeedbackSentiment.parse("bad"));
        assertEquals(FeedbackSentiment.NEUTRAL, FeedbackSentiment.parse("ok"));
    }

    @Test
    void parse_caseInsensitive() {
        assertEquals(FeedbackSentiment.POSITIVE, FeedbackSentiment.parse("Positive"));
        assertEquals(FeedbackSentiment.NEGATIVE, FeedbackSentiment.parse("NEGATIVE"));
    }

    @Test
    void parse_rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> FeedbackSentiment.parse(null));
        assertThrows(IllegalArgumentException.class, () -> FeedbackSentiment.parse(""));
        assertThrows(IllegalArgumentException.class, () -> FeedbackSentiment.parse("   "));
    }

    @Test
    void parse_rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> FeedbackSentiment.parse("love-hate"));
    }
}
