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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Free stock-photo search for the event wizard's cover picker, backed by the
 * Pexels API. Disabled (the "search stock photos" UI hides — see
 * GlobalModelAdvice's "pexelsAvailable") whenever PEXELS_API_KEY is unset, so
 * this is entirely optional infra — mirrors AiContentService.
 */
@Service
public class PexelsService {

    private static final Logger log = LoggerFactory.getLogger(PexelsService.class);
    private static final URI ENDPOINT = URI.create("https://api.pexels.com/v1/search");

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public PexelsService(@Value("${app.pexels.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    public boolean available() {
        return !apiKey.isBlank();
    }

    public record Photo(String id, String thumbnailUrl, String fullUrl,
                        String photographerName, String photographerUrl) {}

    public static class SearchException extends RuntimeException {
        public SearchException(String message, Throwable cause) { super(message, cause); }
    }

    /** Every Pexels photo is free to use under their license (pexels.com/license) —
     *  there's no "paid" tier to filter out; "free images" here just means the
     *  ordinary search results, landscape-oriented to match the event cover shape. */
    public List<Photo> search(String query) {
        if (!available()) throw new IllegalStateException("Pexels search is not configured");
        if (query == null || query.isBlank()) return List.of();
        try {
            String q = java.net.URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(ENDPOINT + "?query=" + q + "&per_page=15&orientation=landscape"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Pexels search failed: HTTP {} — {}", response.statusCode(), truncate(response.body()));
                throw new SearchException("Pexels returned HTTP " + response.statusCode(), null);
            }
            JsonNode root = mapper.readTree(response.body());
            List<Photo> out = new ArrayList<>();
            for (JsonNode p : root.path("photos")) {
                JsonNode src = p.path("src");
                out.add(new Photo(
                        p.path("id").asText(),
                        src.path("medium").asText(null),
                        src.path("large2x").asText(src.path("large").asText(null)),
                        p.path("photographer").asText(null),
                        p.path("photographer_url").asText(null)));
            }
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new SearchException("Failed to reach Pexels", e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s);
    }
}
