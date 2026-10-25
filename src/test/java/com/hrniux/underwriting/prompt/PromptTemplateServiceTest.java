package com.hrniux.underwriting.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hrniux.underwriting.shared.error.PromptRenderException;

class PromptTemplateServiceTest {

    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        service = new PromptTemplateService(
                new InMemoryPromptTemplateRepository(),
                Clock.fixed(Instant.parse("2026-07-13T06:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createsVersionsAndActivatesExactlyOne() {
        PromptTemplateVersion first = service.createVersion(
                "underwriting-analysis", "问题：{{question}}", Set.of("question"));
        PromptTemplateVersion second = service.createVersion(
                "underwriting-analysis", "核保问题：{{question}}", Set.of("question"));

        assertThat(first.version()).isEqualTo(1);
        assertThat(first.active()).isTrue();
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.active()).isFalse();

        PromptTemplateVersion activated = service.activate("underwriting-analysis", 2);

        assertThat(activated.active()).isTrue();
        assertThat(service.versions("underwriting-analysis"))
                .extracting(PromptTemplateVersion::active)
                .containsExactly(false, true);
    }

    @Test
    void treatsPaddedPromptCodesAsOneVersionHistory() {
        service.createVersion("analysis", "original {{question}}", Set.of("question"));
        PromptTemplateVersion second = service.createVersion(
                " analysis ", "replacement {{question}}", Set.of("question"));

        assertThat(second.code()).isEqualTo("analysis");
        assertThat(second.version()).isEqualTo(2);
        assertThat(service.versions(" analysis "))
                .extracting(PromptTemplateVersion::version)
                .containsExactly(1, 2);
        service.activate(" analysis ", 2);
        assertThat(service.versions("analysis"))
                .extracting(PromptTemplateVersion::active)
                .containsExactly(false, true);
        assertThat(service.preview(" analysis ", Map.of("question", "test")))
                .isEqualTo("replacement test");
    }

    @Test
    void rendersTheActiveTemplateWithAllDeclaredVariables() {
        service.createVersion(
                "underwriting-analysis",
                "问题：{{question}}\n证据：{{evidence}}",
                Set.of("question", "evidence"));

        String rendered = service.preview(
                "underwriting-analysis",
                Map.of("question", "是否承保", "evidence", "暴雨规则"));

        assertThat(rendered).isEqualTo("问题：是否承保\n证据：暴雨规则");
    }

    @Test
    void returnsTheExactActiveVersionAndStableTemplateFingerprintWithTheRenderedPrompt() {
        service.createVersion(
                "underwriting-analysis",
                "问题：{{question}}",
                Set.of("question"));

        RenderedPrompt rendered = service.render(
                "underwriting-analysis", Map.of("question", "是否承保"));

        assertThat(rendered.content()).isEqualTo("问题：是否承保");
        assertThat(rendered.snapshot().code()).isEqualTo("underwriting-analysis");
        assertThat(rendered.snapshot().version()).isOne();
        assertThat(rendered.snapshot().templateSha256()).matches("[0-9a-f]{64}");
        assertThat(service.render("underwriting-analysis", Map.of("question", "换一个问题"))
                .snapshot()).isEqualTo(rendered.snapshot());
    }

    @Test
    void rejectsMissingVariables() {
        service.createVersion(
                "underwriting-analysis",
                "问题：{{question}}\n证据：{{evidence}}",
                Set.of("question", "evidence"));

        assertThatThrownBy(() -> service.preview(
                "underwriting-analysis", Map.of("question", "是否承保")))
                .isInstanceOf(PromptRenderException.class)
                .hasMessageContaining("evidence");
    }

    @Test
    void rejectsPlaceholdersThatWereNotDeclared() {
        assertThatThrownBy(() -> service.createVersion(
                "underwriting-analysis",
                "问题：{{question}}\n未声明：{{secret}}",
                Set.of("question")))
                .isInstanceOf(PromptRenderException.class)
                .hasMessageContaining("secret");
    }

    @Test
    void returnsImmutableVersionHistory() {
        service.createVersion("underwriting-analysis", "问题：{{question}}", Set.of("question"));

        assertThatThrownBy(() -> service.versions("underwriting-analysis").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAnInvalidPublishedTemplateFingerprint() {
        assertThatThrownBy(() -> new PromptSnapshot("underwriting-analysis", 1, "not-a-sha256"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }
}
