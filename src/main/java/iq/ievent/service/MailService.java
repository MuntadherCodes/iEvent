package iq.ievent.service;

import iq.ievent.domain.Order;
import iq.ievent.domain.Ticket;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;

/**
 * Sends transactional email. Failures are logged, never propagated — an SMTP
 * outage must not break checkout. Local dev delivers into Mailpit; production
 * uses Mailjet SMTP (configured purely via environment variables).
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final TemplateEngine templates;
    private final String from;
    private final String baseUrl;

    public MailService(JavaMailSender sender,
                       TemplateEngine templates,
                       @Value("${app.mail.from}") String from,
                       @Value("${app.base-url}") String baseUrl) {
        this.sender = sender;
        this.templates = templates;
        this.from = from;
        this.baseUrl = baseUrl;
    }

    @Async
    public void sendOrderConfirmed(Order order, List<Ticket> tickets) {
        send(order.getBuyerEmail(),
             "Your tickets for " + order.getEvent().getTitle() + " — " + order.getOrderCode(),
             "email/order-confirmed", order, tickets);
    }

    @Async
    public void sendOrderPending(Order order) {
        send(order.getBuyerEmail(),
             "Order received — awaiting organizer confirmation (" + order.getOrderCode() + ")",
             "email/order-pending", order, null);
    }

    @Async
    public void sendCampaign(String to, String subject, String bodyText, String eventTitle, String eventUrl) {
        try {
            Context ctx = new Context();
            ctx.setVariable("bodyText", bodyText);
            ctx.setVariable("eventTitle", eventTitle);
            ctx.setVariable("eventUrl", eventUrl);
            ctx.setVariable("baseUrl", baseUrl);
            String html = templates.process("email/campaign", ctx);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (Exception e) {
            log.error("Campaign mail failed to {}: {}", to, e.getMessage());
        }
    }

    @Async
    public void sendOrderRejected(Order order) {
        send(order.getBuyerEmail(),
             "Order " + order.getOrderCode() + " could not be confirmed",
             "email/order-rejected", order, null);
    }

    private void send(String to, String subject, String template, Order order, List<Ticket> tickets) {
        try {
            Context ctx = new Context();
            ctx.setVariable("order", order);
            ctx.setVariable("event", order.getEvent());
            ctx.setVariable("tickets", tickets);
            ctx.setVariable("baseUrl", baseUrl);
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
            log.error("Mail send failed for template {} to {}: {}", template, to, e.getMessage());
        }
    }
}
