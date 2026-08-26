package iq.ievent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-translates event copy (title, summary, description, lineup) between
 * Arabic and English via the Google Cloud Translation API (v2, API-key auth —
 * no service account needed). Disabled (every method silently returns null,
 * same "optional infra" pattern as {@link AiContentService}/PEXELS_API_KEY)
 * whenever GOOGLE_TRANSLATE_API_KEY is unset.
 */
@Service
public class GoogleTranslateService {

    private static final Logger log = LoggerFactory.getLogger(GoogleTranslateService.class);
    private static final URI ENDPOINT = URI.create("https://translation.googleapis.com/language/translate/v2");

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleTranslateService(@Value("${app.google.translate-api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    public boolean available() {
        return !apiKey.isBlank();
    }

    /** Translates one string of plain text or simple HTML. Returns null when
     *  not configured, the input is blank, or the API call fails — callers
     *  fall back to displaying the original-language text in that case. */
    public String translate(String text, String sourceLang, String targetLang, boolean html) {
        if (text == null || text.isBlank()) return null;
        List<String> result = translateBatch(List.of(text), sourceLang, targetLang, html);
        return result == null || result.isEmpty() ? null : result.get(0);
    }

    /** Same as {@link #translate}, but for several strings in one request
     *  (e.g. the lineup's individual lines) — keeps their order and count. */
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang, boolean html) {
        if (!available() || texts.isEmpty()) return null;
        try {
            var body = mapper.createObjectNode();
            var q = body.putArray("q");
            texts.forEach(q::add);
            body.put("source", sourceLang);
            body.put("target", targetLang);
            body.put("format", html ? "html" : "text");

            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + "?key=" + apiKey))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Google Translate request failed: HTTP {} — {}", response.statusCode(), truncate(response.body()));
                return null;
            }
            JsonNode translations = mapper.readTree(response.body()).path("data").path("translations");
            if (!translations.isArray() || translations.size() != texts.size()) {
                log.warn("Google Translate returned an unexpected shape for {} input(s)", texts.size());
                return null;
            }
            List<String> out = new ArrayList<>(texts.size());
            for (JsonNode t : translations) out.add(t.path("translatedText").asText(""));
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to reach Google Translate", e);
            return null;
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s);
    }
}
