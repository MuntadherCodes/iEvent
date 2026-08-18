package iq.ievent.service;

import iq.ievent.domain.Campaign;
import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.repo.CampaignRepository;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Host email campaigns ("email blasts"): resolves an audience to a recipient
 * list, fans the message out through MailService (async) and records a
 * Campaign row for the history panel on /host/marketing.
 *
 * Audiences:
 *  EVENT_ATTENDEES — distinct buyers of CONFIRMED orders for one event
 *  PAST_ATTENDEES  — distinct buyers of CONFIRMED orders across the org's past events
 *  FOLLOWERS       — followers of the organization who allow marketing email
 */
@Service
public class CampaignService {

    private final JdbcTemplate jdbc;
    private final MailService mail;
    private final CampaignRepository campaigns;

    public CampaignService(JdbcTemplate jdbc, MailService mail, CampaignRepository campaigns) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.campaigns = campaigns;
    }

    /** How many people a campaign to this audience would reach right now. */
    @Transactional(readOnly = true)
    public int audienceSize(Organization org, Event eventOrNull, Campaign.Audience audience) {
        return recipients(org, eventOrNull, audience).size();
    }

    /**
     * Resolves recipients, queues one email per recipient (MailService is
     * async) and saves the campaign with its recipient count. Returns the
     * number of recipients.
     */
    @Transactional
    public int send(Organization org, Event eventOrNull, Campaign.Audience audience,
                    String subject, String body, String linkUrl) {
        List<String> to = recipients(org, eventOrNull, audience);
        String title = eventOrNull != null ? eventOrNull.getTitle() : org.getName();
        // NOTE: the parameter named "org" shadows the org.* package root here,
        // so the fully-qualified form doesn't compile — hence the import.
        final java.util.Locale locale = LocaleContextHolder.getLocale();
        for (String email : to) {
            mail.sendCampaign(email, subject, body, title, linkUrl, locale);
        }
        Campaign c = new Campaign();
        c.setOrganization(org);
        c.setEvent(eventOrNull);
        c.setAudience(audience);
        c.setSubject(subject);
        c.setRecipients(to.size());
        campaigns.save(c);
        return to.size();
    }

    private List<String> recipients(Organization org, Event event, Campaign.Audience audience) {
        switch (audience) {
            case EVENT_ATTENDEES:
                if (event == null) return List.of();
                return jdbc.queryForList("""
                        SELECT DISTINCT o.buyer_email FROM orders o
                        WHERE o.event_id = ? AND o.status = 'CONFIRMED'
                        """, String.class, event.getId());
            case PAST_ATTENDEES:
                return jdbc.queryForList("""
                        SELECT DISTINCT o.buyer_email FROM orders o
                        JOIN events e ON e.id = o.event_id
                        WHERE e.organization_id = ? AND o.status = 'CONFIRMED'
                          AND e.starts_at < now()
                        """, String.class, org.getId());
            case FOLLOWERS:
                return jdbc.queryForList("""
                        SELECT u.email FROM follows f
                        JOIN users u ON u.id = f.user_id
                        WHERE f.organization_id = ? AND u.notify_marketing = TRUE
                        """, String.class, org.getId());
            default:
                return List.of();
        }
    }
}
