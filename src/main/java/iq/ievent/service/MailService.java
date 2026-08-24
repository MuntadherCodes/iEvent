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
    private final TicketPdfService ticketPdf;
    private final String from;
    private final String baseUrl;
    private final String supportEmail;

    public MailService(JavaMailSender sender,
                       TemplateEngine templates,
                       MessageSource messages,
                       TicketPdfService ticketPdf,
                       @Value("${app.mail.from}") String from,
                       @Value("${app.base-url}") String baseUrl,
                       @Value("${app.mail.support}") String supportEmail) {
        this.sender = sender;
        this.templates = templates;
        this.messages = messages;
        this.ticketPdf = ticketPdf;
        this.from = from;
        this.baseUrl = baseUrl;
        this.supportEmail = supportEmail;
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

    /** A brand-new invite: the recipient has no iEvent account yet (or the
     *  organizer chose to invite by email regardless) — the button links to
     *  the public accept-invite page, which prompts sign-in/registration
     *  before actually joining the team. */
    @Async
    public void sendTeamInvite(String to, String orgName, String roleLabel, String acceptUrl, Locale locale) {
        sendTeamMail(to, msg("mail.teamInvite.subject", locale, orgName),
                msg("mail.teamInvite.body", locale, orgName, roleLabel),
                acceptUrl, msg("mail.teamInvite.accept", locale), locale);
    }

    /** The recipient already had an account and was added immediately — no
     *  accept step needed, so the button just goes straight to the dashboard. */
    @Async
    public void sendTeamAdded(String to, String orgName, String roleLabel, Locale locale) {
        sendTeamMail(to, msg("mail.teamAdded.subject", locale, orgName),
                msg("mail.teamAdded.body", locale, orgName, roleLabel),
                baseUrl + "/host", msg("mail.teamAdded.goDashboard", locale), locale);
    }

    /** Support contact form (public site + host dashboard). Synchronous — unlike
     *  the transactional sends above, the caller needs to know whether it
     *  actually went out to decide which confirmation to show the submitter. */
    public boolean sendSupportContact(String name, String fromEmail, String topic, String messageText, Locale locale) {
        Locale loc = safe(locale);
        try {
            Context ctx = new Context(loc);
            ctx.setVariable("name", name);
            ctx.setVariable("fromEmail", fromEmail);
            ctx.setVariable("topic", topic);
            ctx.setVariable("messageText", messageText);
            ctx.setVariable("mailLang", isEnglish(loc) ? "en" : "ar");
            ctx.setVariable("mailDir", isEnglish(loc) ? "ltr" : "rtl");
            String html = templates.process("email/support-contact", ctx);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(supportEmail);
            helper.setReplyTo(fromEmail);
            helper.setSubject("[iEvent support] " + (topic == null || topic.isBlank() ? "General" : topic) + " — " + name);
            helper.setText(html, true);
            sender.send(message);
            log.info("Sent support contact mail from {} ({})", fromEmail, topic);
            return true;
        } catch (Exception e) {
            log.error("Support contact mail failed from {}", fromEmail, e);
            return false;
        }
    }

    private void sendTeamMail(String to, String subject, String bodyText, String buttonUrl, String buttonLabel, Locale locale) {
        Locale loc = safe(locale);
        Locale previous = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(loc);
        try {
            Context ctx = new Context(loc);
            ctx.setVariable("bodyText", bodyText);
            ctx.setVariable("buttonUrl", buttonUrl);
            ctx.setVariable("buttonLabel", buttonLabel);
            ctx.setVariable("mailLang", isEnglish(loc) ? "en" : "ar");
            ctx.setVariable("mailDir", isEnglish(loc) ? "ltr" : "rtl");
            String html = templates.process("email/team-invite", ctx);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Sent team mail to {}", to);
        } catch (Exception e) {
            log.error("Team mail failed to {}", to, e);
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
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

            // Tickets are only ever passed for the confirmed email — free
            // orders confirm instantly at checkout, paid ones only once the
            // organizer approves — so attaching here covers both cases: the
            // moment tickets are valid, the buyer has the PDF in their inbox.
            boolean attachTickets = tickets != null && !tickets.isEmpty();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, attachTickets, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            if (attachTickets) {
                byte[] pdf = ticketPdf.ticketsPdf(tickets);
                helper.addAttachment(order.getOrderCode() + "-tickets.pdf",
                        new org.springframework.core.io.ByteArrayResource(pdf), "application/pdf");
            }
            sender.send(message);
            log.info("Sent mail '{}' to {}", template, to);
        } catch (Exception e) {
            log.error("Mail send failed for template {} to {}", template, to, e);
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }
}
