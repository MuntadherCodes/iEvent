package iq.ievent.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translations live in flat JSON files — classpath:i18n/en/*.json and
 * classpath:i18n/ar/*.json — with identical keys in both languages
 * ("nav.browse": "Browse events" / "تصفح الفعاليات"). Multiple files per
 * language are merged so different areas (public/host/wizard/emails) own
 * their own file. Missing Arabic keys fall back to English, and a missing
 * key renders as the key itself (visible, greppable, never a crash).
 *
 * Registered as the app's "messageSource" bean, so Thymeleaf #{...} and
 * MessageSource injections all read from these JSON files.
 */
@Component("messageSource")
public class JsonMessageSource extends AbstractMessageSource {

    private static final Logger log = LoggerFactory.getLogger(JsonMessageSource.class);

    private final Map<String, Map<String, String>> byLang = new HashMap<>();

    public JsonMessageSource() {
        byLang.put("en", load("en"));
        byLang.put("ar", load("ar"));
        log.info("i18n loaded: {} en keys, {} ar keys",
                byLang.get("en").size(), byLang.get("ar").size());
        setUseCodeAsDefaultMessage(true);
    }

    private Map<String, String> load(String lang) {
        Map<String, String> merged = new HashMap<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:i18n/" + lang + "/*.json");
            for (Resource file : files) {
                try (var in = file.getInputStream()) {
                    Map<String, String> keys = mapper.readValue(in, new TypeReference<>() {});
                    for (var e : keys.entrySet()) {
                        if (merged.put(e.getKey(), e.getValue()) != null) {
                            log.warn("i18n duplicate key '{}' in {} ({})", e.getKey(), file.getFilename(), lang);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("i18n load failed for '{}'", lang, e);
        }
        return merged;
    }

    private String raw(String code, Locale locale) {
        String lang = locale != null && "en".equals(locale.getLanguage()) ? "en" : "ar";
        String v = byLang.getOrDefault(lang, Map.of()).get(code);
        if (v == null) v = byLang.getOrDefault("en", Map.of()).get(code);
        return v;
    }

    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        return raw(code, locale);
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        String v = raw(code, locale);
        if (v == null) return null;
        // JSON strings are plain text; escape MessageFormat's quote handling
        // so apostrophes survive when arguments are used.
        return new MessageFormat(v.replace("'", "''"), locale == null ? new Locale("ar") : locale);
    }
}
