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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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
        return generate(kind, title, category, arabic, null, null);
    }

    /** {@code startTime}/{@code endTime}, when given, are "HH:mm" 24h strings (as
     *  submitted by the wizard's time fields) — only meaningful for {@link Kind#LINEUP},
     *  so the suggested agenda items' times actually fall within the real schedule
     *  instead of the generic placeholder times used when they're absent. */
    public String generate(Kind kind, String title, String category, boolean arabic, String startTime, String endTime) {
        if (!available()) throw new IllegalStateException("AI writing is not configured");
        String system = systemPrompt(kind, arabic);
        StringBuilder user = new StringBuilder("Event title: ").append(title);
        if (category != null && !category.isBlank()) user.append("\nCategory: ").append(category);
        if (kind == Kind.LINEUP) {
            String start = friendlyTime(startTime);
            String end = friendlyTime(endTime);
            if (start != null) user.append("\nEvent start time: ").append(start);
            if (end != null) user.append("\nEvent end time: ").append(end);
        }

        return callChat(system, user.toString(), false);
    }

    /** Subject + body for a marketing email campaign, generated together so
     *  they stay consistent with each other. {@code eventTitle}/category/
     *  dateLine/venue are the real, already-formatted event details (or all
     *  null when no specific event is selected) — never invented by the model. */
    public record CampaignCopy(String subject, String body) {}

    public CampaignCopy generateCampaign(String eventTitle, String category, String dateLine, String venue, boolean arabic) {
        if (!available()) throw new IllegalStateException("AI writing is not configured");
        StringBuilder user = new StringBuilder();
        if (eventTitle != null && !eventTitle.isBlank()) {
            user.append("Event title: ").append(eventTitle);
            if (category != null && !category.isBlank()) user.append("\nCategory: ").append(category);
            if (dateLine != null && !dateLine.isBlank()) user.append("\nDate & time: ").append(dateLine);
            if (venue != null && !venue.isBlank()) user.append("\nVenue: ").append(venue);
        } else {
            user.append("No specific event is selected — write a general friendly update for followers "
                    + "about the organizer's upcoming events.");
        }
        String raw = callChat(campaignSystemPrompt(arabic), user.toString(), true);
        try {
            JsonNode json = mapper.readTree(raw);
            String subject = json.path("subject").asText("").strip();
            String bodyText = json.path("body").asText("").strip();
            if (subject.isBlank() || bodyText.isBlank()) {
                throw new GenerationException("OpenAI campaign response missing subject/body", null);
            }
            return new CampaignCopy(subject, bodyText);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new GenerationException("OpenAI campaign response wasn't valid JSON", e);
        }
    }

    private static String campaignSystemPrompt(boolean arabic) {
        return arabic
                ? "أنت تكتب رسائل بريد إلكتروني تسويقية قصيرة بالعربية الفصحى لمنصّة بيع تذاكر الفعاليات في العراق، تُرسل "
                  + "للمتابعين أو الحاضرين السابقين. عند توفر تفاصيل فعالية حقيقية في رسالة المستخدم، اكتب "
                  + "عنوانًا جذابًا (بحد أقصى 60 حرفًا تقريبًا) ونص رسالة ودّي من 2 إلى 4 جمل قصيرة يذكر "
                  + "تفاصيل الفعالية الفعلية بشكل طبيعي (الاسم، التاريخ والوقت، المكان) — لا تخترع أي تفاصيل "
                  + "غير مذكورة. إن لم تُذكر أي فعالية، اكتب تحديثًا عامًا وديًا بدلاً من ذلك. أجب حصرًا بكائن "
                  + "JSON بهذا الشكل: {\"subject\": \"...\", \"body\": \"...\"} — بدون Markdown أو أسوار كود "
                  + "أو أي نص إضافي خارج الـ JSON."
                : "You write short marketing emails for an event ticketing platform in Iraq, sent to "
                  + "followers or past attendees. When real event details are given in the user message, "
                  + "write a punchy subject line (roughly 60 characters max) and a friendly 2-4 sentence "
                  + "email body that mentions the real details naturally (name, date/time, venue) — never "
                  + "invent details that weren't given. If no event is mentioned, write a generic friendly "
                  + "update instead. Respond with ONLY a JSON object of exactly this shape: "
                  + "{\"subject\": \"...\", \"body\": \"...\"} — no Markdown, no code fences, no extra text "
                  + "outside the JSON.";
    }

    private String callChat(String system, String userContent, boolean jsonMode) {
        try {
            var body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.7);
            if (jsonMode) body.putObject("response_format").put("type", "json_object");
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", userContent);

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
                    ? "أنت كاتب محتوى لمنصّة بيع تذاكر الفعاليات في العراق. اكتب بالعربية الفصحى فقط، دون أي لهجة عامية. اكتب محتوى صفحة الفعالية بصيغة "
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
                    ? "أنت كاتب محتوى لمنصّة بيع تذاكر الفعاليات في العراق. اكتب بالعربية الفصحى فقط، دون أي لهجة عامية. اكتب جملة واحدة قصيرة وجذابة "
                      + "(بحد أقصى 140 حرفاً) تُستخدم كملخص يظهر على بطاقة الفعالية في الدليل. نص عادي بدون "
                      + "علامات اقتباس أو Markdown. طابق الأسلوب مع نوع الفعالية وعنوانها."
                    : "You write concise, punchy one-line summaries for event directory cards on a "
                      + "ticketing platform in Iraq. Write ONE short sentence, max 140 characters, plain text, "
                      + "no quotes or Markdown. Match the tone to the event's category and title.";
            case LINEUP -> arabic
                    ? "أنت تساعد منظّم فعاليات في العراق على صياغة مسودة أولية لجدول أعمال أو قائمة فقرات، بالعربية الفصحى دون أي لهجة عامية. "
                      + "اكتب 3 إلى 5 أسطر، كل سطر بصيغة \"اسم الفقرة — وقت أو ملاحظة عامة\" نص عادي بدون "
                      + "ترقيم أو رموز Markdown، سطر واحد لكل فقرة. طابق المحتوى مع نوع الفعالية وعنوانها. "
                      + "إذا تضمّنت رسالة المستخدم وقت بدء و/أو انتهاء الفعالية الفعليين، اجعل كل وقت مقترح "
                      + "ضمن هذا النطاق فقط وبترتيب زمني تصاعدي (مثلاً فتح الأبواب قبيل وقت البدء مباشرة، "
                      + "وآخر فقرة قبيل وقت الانتهاء أو عنده). إن لم يُذكر أي وقت، استخدم أوقاتاً عامة تقريبية "
                      + "بما أنها مجرد مسودة يعدّلها المنظّم."
                    : "You help event organizers in Iraq draft a starting agenda / lineup. Write 3-5 lines, "
                      + "each in the form \"Item name — approximate time or note\". Plain text, one line "
                      + "per item, no numbering or Markdown. Match the content to the event's category and "
                      + "title. If the user message includes the event's actual start and/or end time, every "
                      + "suggested time must fall strictly within that window and appear in chronological "
                      + "order (e.g. doors open shortly after the start time, the closing item near the end "
                      + "time). If no time is given, use generic approximate times since this is only a draft "
                      + "for the organizer to adjust.";
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

    /** "18:15" -> "6:15 PM"; null/blank/unparseable input yields null (omitted from the prompt). */
    private static String friendlyTime(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return null;
        try {
            return LocalTime.parse(hhmm).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s);
    }
}
