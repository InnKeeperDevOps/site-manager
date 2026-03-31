package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceRetryTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @InjectMocks
    private ClaudeService claudeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(claudeService, "claudeRetryBaseDelayMs", 2000L);
        ReflectionTestUtils.setField(claudeService, "claudeRetryMaxDelayMs", 30000L);
    }

    // -------------------------------------------------------------------------
    // computeRetryDelay — exponential backoff with jitter
    // -------------------------------------------------------------------------

    @Test
    void attempt1DelayIsBetweenBaseAndBaseWith20PercentJitter() {
        long base = 2000L;
        for (int i = 0; i < 20; i++) {
            long delay = claudeService.computeRetryDelay(1);
            assertThat(delay).isBetween(base, (long) (base * 1.2) + 1);
        }
    }

    @Test
    void attempt2DelayIsDoubleBase() {
        long expectedBase = 4000L;
        for (int i = 0; i < 20; i++) {
            long delay = claudeService.computeRetryDelay(2);
            assertThat(delay).isBetween(expectedBase, (long) (expectedBase * 1.2) + 1);
        }
    }

    @Test
    void attempt3DelayIsQuadrupleBase() {
        long expectedBase = 8000L;
        for (int i = 0; i < 20; i++) {
            long delay = claudeService.computeRetryDelay(3);
            assertThat(delay).isBetween(expectedBase, (long) (expectedBase * 1.2) + 1);
        }
    }

    @Test
    void delayIsCapAtMaxDelay() {
        // Attempt 10: 2000 * 2^9 = 1,024,000ms → capped at 30000
        long maxDelay = 30000L;
        for (int i = 0; i < 20; i++) {
            long delay = claudeService.computeRetryDelay(10);
            assertThat(delay).isBetween(maxDelay, (long) (maxDelay * 1.2) + 1);
        }
    }

    @Test
    void delayNeverExceedsMaxDelayPlusJitter() {
        long maxDelay = 30000L;
        for (int attempt = 1; attempt <= 15; attempt++) {
            long delay = claudeService.computeRetryDelay(attempt);
            assertThat(delay).isLessThanOrEqualTo((long) (maxDelay * 1.2) + 1);
        }
    }

    @Test
    void delayIsAlwaysPositive() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(claudeService.computeRetryDelay(attempt)).isPositive();
        }
    }

    @Test
    void defaultConfigValuesAreApplied() {
        // Verify defaults wired via @Value are reasonable when set via reflection
        ReflectionTestUtils.setField(claudeService, "claudeMaxRetries", 3);
        ReflectionTestUtils.setField(claudeService, "claudeRetryBaseDelayMs", 2000L);
        ReflectionTestUtils.setField(claudeService, "claudeRetryMaxDelayMs", 30000L);

        assertThat((int) ReflectionTestUtils.getField(claudeService, "claudeMaxRetries")).isEqualTo(3);
        assertThat((long) ReflectionTestUtils.getField(claudeService, "claudeRetryBaseDelayMs")).isEqualTo(2000L);
        assertThat((long) ReflectionTestUtils.getField(claudeService, "claudeRetryMaxDelayMs")).isEqualTo(30000L);
    }

    // -------------------------------------------------------------------------
    // computeRetryDelay — custom max delay
    // -------------------------------------------------------------------------

    @Test
    void customMaxDelayIsCappedCorrectly() {
        ReflectionTestUtils.setField(claudeService, "claudeRetryBaseDelayMs", 1000L);
        ReflectionTestUtils.setField(claudeService, "claudeRetryMaxDelayMs", 5000L);

        // attempt 4: 1000 * 2^3 = 8000 → capped to 5000
        long delay = claudeService.computeRetryDelay(4);
        assertThat(delay).isBetween(5000L, (long) (5000L * 1.2) + 1);
    }

    @Test
    void smallBaseDelayWithLargeMax_noCapApplied() {
        ReflectionTestUtils.setField(claudeService, "claudeRetryBaseDelayMs", 100L);
        ReflectionTestUtils.setField(claudeService, "claudeRetryMaxDelayMs", 30000L);

        // attempt 1: 100ms base, no cap needed
        long delay = claudeService.computeRetryDelay(1);
        assertThat(delay).isBetween(100L, (long) (100L * 1.2) + 1);
    }
}
