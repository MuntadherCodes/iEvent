package iq.ievent.service;

import iq.ievent.domain.Order;
import iq.ievent.domain.Ticket;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Locale;

/**
 * Sends transactional email. Failures are logged, never propagated — an SMTP
 * outage must not break checkout. Local dev delivers into Mailpit; production
 * uses Mailjet SMTP (configured purely via environment variables).
 *
 * Locale contract: every public mail method takes a trailing {@link Locale}
 * argument — evaluated on the CALLER's thread (pass
 * LocaleContextHolder.getLocale()) because @Async bodies run on the mail
 * executor where the holder falls back to the site default (ar). Templates
 * render with that locale plus the variables {@code mailLang} ("ar"/"en") and
 * {@code mailDir} ("rtl"/"ltr"); subjects resolve through the MessageSource.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final TemplateEngine templates;
    private final MessageSource messages;
    private final String from;
    private final String baseUrl;

    public MailService(JavaMailSender sender,
                       TemplateEngine templates,
                       MessageSource messages,
                       @Value("${app.mail.from}") String from,
                       @Value("${app.base-url}") String baseUrl) {
        this.sender = sender;
        this.templates = templates;
        this.messages = messages;
        this.from = from;
        this.baseUrl = baseUrl;
    }

    private static Locale safe(Locale locale) {
        return locale == null ? new Locale("ar") : locale;
    }

    private static boolean isEnglish(Locale locale) {
        return "en".equals(safe(locale).getLanguage());
    }

    private String msg(String code, Locale locale, Object... args) {
        return messages.getMessage(code, args, safe(locale));
    }

    @Async
    public void sendOrderConfirmed(Order order, List<Ticket> tickets, Locale locale) {
        send(order.getBuyerEmail(),
             msg("mail.orderConfirmed.subject", locale,
                     order.getEvent().getTitle(), order.getOrderCode()),
             "email/order-confirmed", order, tickets, locale);
    }

    @Async
    public void sendOrderPending(Order order, Locale locale) {
        send(order.getBuyerEmail(),
             msg("mail.orderPending.subject", locale, order.getOrderCode()),
             "email/order-pending", order, null, locale);
    }

    @Async
    public void sendCampaign(String to, String subject, String bodyText, String eventTitle,
                             String eventUrl, Locale locale) {
        Locale loc = safe(locale);
        Locale previous = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(loc);
        try {
            Context ctx = new Context(loc);
            ctx.setVariable("bodyText", bodyText);
            ctx.setVariable("eventTitle", eventTitle);
            ctx.setVariable("eventUrl", eventUrl);
            ctx.setVariable("baseUrl", baseUrl);
            ctx.setVariable("mailLang", isEnglish(loc) ? "en" : "ar");
            ctx.setVariable("mailDir", isEnglish(loc) ? "ltr" : "rtl");
            String html = templates.process("email/campaign", ctx);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (Exception e) {
            log.error("Campaign mail failed to {}", to, e);
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }

    @Async
    public void sendOrderRefunded(Order order, Locale locale) {
        send(order.getBuyerEmail(),
             msg("mail.orderRefunded.subject", locale,
                     order.getOrderCode(), order.getEvent().getTitle()),
             "email/order-refunded", order, null, locale);
    }

    @Async
    public void sendPendingOrderAlert(String hostEmail, Order order, Locale locale) {
        Locale previous = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(safe(locale));
        try {
            sendCampaign(hostEmail,
                    msg("mail.pendingAlert.subject", locale, order.getOrderCode()),
                    msg("mail.pendingAlert.body", locale,
                            order.getEvent().getTitle(), Format.iqd(order.getTotalIqd())),
                    order.getEvent().getTitle(), baseUrl + "/host/orders?status=pending", locale);
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }

    @Async
    public void sendPasswordReset(String to, String resetUrl, Locale locale) {
        sendCampaign(to, msg("mail.passwordReset.subject", locale),
                msg("mail.passwordReset.body", locale),
                msg("mail.passwordReset.title", locale), resetUrl, locale);
    }

    @Async
    public void sendOrderRejected(Order order, Locale locale) {
        send(order.getBuyerEmail(),
             msg("mail.orderRejected.subject", locale, order.getOrderCode()),
             "email/order-rejected", order, null, locale);
    }

    private void send(String to, String subject, String template, Order order,
                      List<Ticket> tickets, Locale locale) {
        Locale loc = safe(locale);
        Locale previous = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(loc); // Format.* inside the render localizes correctly
        try {
            Context ctx = new Context(loc);
            ctx.setVariable("order", order);
            ctx.setVariable("event", order.getEvent());
            ctx.setVariable("tickets", tickets);
            ctx.setVariable("baseUrl", baseUrl);
            ctx.setVariable("mailLang", isEnglish(loc) ? "en" : "ar");
            ctx.setVariable("mailDir", isEnglish(loc) ? "ltr" : "rtl");
            ctx.setVariable("totalLabel", Format.iqd(order.getTotalIqd()));
            ctx.setVariable("dateLine", Format.longDateLine(
                    order.getEvent().getStartsAt(), order.getEvent().getEndsAt()));
            String html = templates.process(template, ctx);

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Sent mail '{}' to {}", template, to);
        } catch (Exception e) {
            log.error("Mail send failed for template {} to {}", template, to, e);
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }
}
