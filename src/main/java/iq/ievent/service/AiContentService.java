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

/**
 * Generates event copy via the OpenAI API for the wizard's "Write it for me"
 * button. Disabled (the button hides — see GlobalModelAdvice's "aiAvailable")
 * whenever OPENAI_API_KEY is unset, so this is entirely optional infra.
 */
@Service
public class AiContentService {

    private static final Logger log = LoggerFactory.getLogger(AiContentService.class);
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AiContentService(@Value("${app.openai.api-key:}") String apiKey,
                            @Value("${app.openai.model:gpt-4o-mini}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
    }

    public boolean available() {
        return !apiKey.isBlank();
    }

    /** Thrown on any failure to reach/parse OpenAI — callers show a generic retry message. */
    public static class GenerationException extends RuntimeException {
        public GenerationException(String message, Throwable cause) { super(message, cause); }
    }

    /** Which field the "Write it for me" button is generating text for. */
    public enum Kind { DESCRIPTION, SUMMARY, LINEUP }

    /**
     * Writes ready-to-use event copy in the requested language, tailored to the
     * event's title and category. Plain text, no Markdown — inserted as-is
     * (DESCRIPTION splits on blank lines into &lt;p&gt; paragraphs client-side;
     * SUMMARY/LINEUP go straight into their plain textareas).
     */
    public String generate(Kind kind, String title, String category, boolean arabic) {
        if (!available()) throw new IllegalStateException("AI writing is not configured");
        String system = systemPrompt(kind, arabic);
        StringBuilder user = new StringBuilder("Event title: ").append(title);
        if (category != null && !category.isBlank()) user.append("\nCategory: ").append(category);

        try {
            var body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.7);
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", user.toString());

            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OpenAI request failed: HTTP {} — {}", response.statusCode(), truncate(response.body()));
                throw new GenerationException("OpenAI returned HTTP " + response.statusCode(), null);
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new GenerationException("OpenAI response had no content", null);
            }
            return stripCodeFence(content.asText().strip());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new GenerationException("Failed to reach OpenAI", e);
        }
    }

    private static String systemPrompt(Kind kind, boolean arabic) {
        return switch (kind) {
            case DESCRIPTION -> arabic
                    ? "أنت كاتب محتوى لمنصّة بيع تذاكر الفعاليات في العراق. اكتب محتوى صفحة الفعالية بصيغة "
                      + "HTML بسيطة، باستخدام الوسوم <h4> و<p> و<b> فقط (بدون Markdown وبدون أي وسوم أخرى)، "
                      + "بالبنية التالية بالضبط:\n"
                      + "<h4>الوصف:</h4> ثم فقرتان إلى ثلاث فقرات <p> تصف الفعالية بأسلوب جذاب يطابق نوعها "
                      + "وعنوانها.\n"
                      + "<h4>ملاحظات مهمة للحضور:</h4> ثم فقرة <p> واحدة بنصائح عملية عامة (مثل الوصول مبكراً "
                      + "أو ارتداء ملابس مناسبة).\n"
                      + "<h4>أسئلة شائعة:</h4> ثم 3 إلى 4 فقرات <p>، كل فقرة بصيغة <b>سؤال؟</b> — إجابة عامة "
                      + "قصيرة.\n"
                      + "لا تخترع حقائق محددة مثل الأسعار أو المواقف أو الأوقات الدقيقة — إن لزم الأمر استخدم "
                      + "صياغة عامة (مثل الإشارة إلى صفحة الفعالية للتفاصيل) بدلاً من اختلاق رقم أو وقت."
                    : "You write event-page content for a ticketing platform in Iraq, as simple HTML using "
                      + "ONLY the <h4>, <p>, and <b> tags (no Markdown, no other tags), in exactly this "
                      + "structure:\n"
                      + "<h4>Description:</h4> then 2-3 <p> paragraphs describing the event, matching its "
                      + "category and title.\n"
                      + "<h4>Important notes for attendees:</h4> then one <p> paragraph with general practical "
                      + "advice (e.g. arrive early, dress appropriately).\n"
                      + "<h4>Frequently asked questions:</h4> then 3-4 <p> paragraphs, each formatted as "
                      + "<b>Question?</b> — a short, general answer.\n"
                      + "Do not invent specific facts such as prices, parking, or exact times — if needed, "
                      + "phrase generally (e.g. pointing to the event page for details) instead of making up "
                      + "a number or time.";
            case SUMMARY -> arabic
                    ? "أنت كاتب محتوى لمنصّة بيع تذاكر الفعاليات في العراق. اكتب جملة واحدة قصيرة وجذابة "
                      + "(بحد أقصى 140 حرفاً) تُستخدم كملخص يظهر على بطاقة الفعالية في الدليل. نص عادي بدون "
                      + "علامات اقتباس أو Markdown. طابق الأسلوب مع نوع الفعالية وعنوانها."
                    : "You write concise, punchy one-line summaries for event directory cards on a "
                      + "ticketing platform in Iraq. Write ONE short sentence, max 140 characters, plain text, "
                      + "no quotes or Markdown. Match the tone to the event's category and title.";
            case LINEUP -> arabic
                    ? "أنت تساعد منظّم فعاليات في العراق على صياغة مسودة أولية لجدول أعمال أو قائمة فقرات. "
                      + "اكتب 3 إلى 5 أسطر، كل سطر بصيغة \"اسم الفقرة — وقت أو ملاحظة عامة\" (استخدم أوقاتاً "
                      + "عامة تقريبية وليست دقيقة، لأنها مجرد مسودة يعدّلها المنظّم). نص عادي بدون ترقيم أو "
                      + "رموز Markdown، سطر واحد لكل فقرة. طابق المحتوى مع نوع الفعالية وعنوانها."
                    : "You help event organizers in Iraq draft a starting agenda / lineup. Write 3-5 lines, "
                      + "each in the form \"Item name — approximate time or note\" (use generic, approximate "
                      + "times since this is only a draft for the organizer to adjust). Plain text, one line "
                      + "per item, no numbering or Markdown. Match the content to the event's category and title.";
        };
    }

    /** Strips a ```html ... ``` (or bare ```) fence some models wrap output in, despite instructions not to. */
    private static String stripCodeFence(String s) {
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline != -1) {
                s = s.substring(firstNewline + 1);
                int lastFence = s.lastIndexOf("```");
                if (lastFence != -1) s = s.substring(0, lastFence);
            }
        }
        return s.strip();
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s);
    }
}
