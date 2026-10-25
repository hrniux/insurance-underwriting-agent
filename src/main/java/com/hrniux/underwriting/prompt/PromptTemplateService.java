package com.hrniux.underwriting.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hrniux.underwriting.shared.error.PromptRenderException;
import com.hrniux.underwriting.shared.error.ResourceNotFoundException;
import com.hrniux.underwriting.shared.validation.PathResourceIdentifier;

@Service
public class PromptTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)}}" );

    private final PromptTemplateRepository repository;
    private final Clock clock;

    @Autowired
    public PromptTemplateService(PromptTemplateRepository repository) {
        this(repository, Clock.systemUTC());
    }

    PromptTemplateService(PromptTemplateRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public synchronized PromptTemplateVersion createVersion(
            String code, String body, Set<String> requiredVariables) {
        String normalizedCode = normalizeCode(code);
        Set<String> declared = Set.copyOf(Objects.requireNonNull(
                requiredVariables, "requiredVariables must not be null"));
        Set<String> placeholders = placeholders(body);
        if (!declared.equals(placeholders)) {
            Set<String> undeclared = new LinkedHashSet<>(placeholders);
            undeclared.removeAll(declared);
            Set<String> unused = new LinkedHashSet<>(declared);
            unused.removeAll(placeholders);
            throw new PromptRenderException(
                    "Prompt variables do not match; undeclared=" + undeclared + ", unused=" + unused);
        }

        List<PromptTemplateVersion> history = repository.findByCode(normalizedCode);
        int nextVersion = history.stream()
                .mapToInt(PromptTemplateVersion::version)
                .max()
                .orElse(0) + 1;
        PromptTemplateVersion template = new PromptTemplateVersion(
                normalizedCode, nextVersion, body, declared, history.isEmpty(), clock.instant());
        return repository.save(template);
    }

    public synchronized PromptTemplateVersion activate(String code, int version) {
        String normalizedCode = normalizeCode(code);
        PromptTemplateVersion selected = repository.findByCodeAndVersion(normalizedCode, version)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROMPT_VERSION_NOT_FOUND", normalizedCode + ":" + version));
        List<PromptTemplateVersion> updated = repository.findByCode(normalizedCode).stream()
                .map(template -> template.withActive(template.version() == version))
                .toList();
        repository.replace(normalizedCode, updated);
        return selected.withActive(true);
    }

    public PromptTemplateVersion active(String code) {
        String normalizedCode = normalizeCode(code);
        return repository.findByCode(normalizedCode).stream()
                .filter(PromptTemplateVersion::active)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("PROMPT_NOT_FOUND", normalizedCode));
    }

    public PromptTemplateVersion getVersion(String code, int version) {
        String normalizedCode = normalizeCode(code);
        return repository.findByCodeAndVersion(normalizedCode, version)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROMPT_VERSION_NOT_FOUND", normalizedCode + ":" + version));
    }

    public List<PromptTemplateVersion> versions(String code) {
        return List.copyOf(repository.findByCode(normalizeCode(code)));
    }

    public List<PromptTemplateVersion> listAll() {
        return List.copyOf(repository.findAll());
    }

    public String preview(String code, Map<String, ?> variables) {
        return render(code, variables).content();
    }

    public RenderedPrompt render(String code, Map<String, ?> variables) {
        PromptTemplateVersion template = active(code);
        Map<String, ?> supplied = Map.copyOf(Objects.requireNonNull(variables, "variables must not be null"));
        Set<String> missing = new LinkedHashSet<>(template.requiredVariables());
        missing.removeAll(supplied.keySet());
        if (!missing.isEmpty()) {
            throw new PromptRenderException("Missing prompt variables: " + missing);
        }

        Matcher matcher = PLACEHOLDER.matcher(template.body());
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = String.valueOf(supplied.get(matcher.group(1)));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return new RenderedPrompt(
                new PromptSnapshot(template.code(), template.version(), sha256(template.body())),
                rendered.toString());
    }

    private String normalizeCode(String code) {
        return PathResourceIdentifier.require(code, "code");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private Set<String> placeholders(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return Set.copyOf(names);
    }
}
