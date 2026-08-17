package iq.ievent.service;

import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.domain.TrackingLink;
import iq.ievent.repo.TrackingLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

/** Short share links (/l/{code}) with per-channel click counting. */
@Service
public class TrackingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    // No look-alike characters (0/O, 1/l/I) — codes get read out loud and retyped.
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private final TrackingLinkRepository links;

    public TrackingService(TrackingLinkRepository links) {
        this.links = links;
    }

    @Transactional
    public TrackingLink create(Organization org, Event event, String channel) {
        TrackingLink link = new TrackingLink();
        link.setOrganization(org);
        link.setEvent(event);
        link.setChannel(channel == null || channel.isBlank() ? "other" : channel.trim().toLowerCase());
        String code;
        do {
            code = randomCode(7);
        } while (links.existsByCode(code));
        link.setCode(code);
        return links.save(link);
    }

    @Transactional(readOnly = true)
    public List<TrackingLink> forOrganization(Long orgId) {
        return links.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional
    public void delete(Long linkId, Long orgId) {
        links.findById(linkId)
                .filter(l -> l.getOrganization().getId().equals(orgId))
                .ifPresent(links::delete);
    }

    /** Resolves a code to its event slug and counts the click. Empty if unknown. */
    @Transactional
    public Optional<String> resolveAndCount(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        Optional<TrackingLink> link = links.findByCode(code.trim());
        link.ifPresent(l -> links.incrementClicks(l.getCode()));
        return link.map(l -> l.getEvent().getSlug());
    }

    private static String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
