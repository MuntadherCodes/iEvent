package iq.ievent.service;

import iq.ievent.domain.Event;
import iq.ievent.domain.PromoCode;
import iq.ievent.repo.PromoCodeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PromoService {

    public record Applied(PromoCode promo, long discountIqd) {}

    private final PromoCodeRepository promos;
    private final JdbcTemplate jdbc;

    public PromoService(PromoCodeRepository promos, JdbcTemplate jdbc) {
        this.promos = promos;
        this.jdbc = jdbc;
    }

    /** Validates a code for an event; returns the discount for the given subtotal. */
    @Transactional(readOnly = true)
    public Optional<Applied> preview(Event event, String code, long subtotalIqd) {
        if (code == null || code.isBlank() || subtotalIqd <= 0) return Optional.empty();
        return promos.findByOrgAndCode(event.getOrganization().getId(), code.trim())
                .filter(PromoCode::isActive)
                .filter(p -> p.getEventId() == null || p.getEventId().equals(event.getId()))
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(OffsetDateTime.now()))
                .filter(p -> p.getMaxUses() == 0 || p.getUsed() < p.getMaxUses())
                .map(p -> new Applied(p, discount(p, subtotalIqd)));
    }

    /** Atomically consumes one use; returns false if the code just ran out. */
    @Transactional
    public boolean redeem(PromoCode promo) {
        int updated = jdbc.update("""
                UPDATE promo_codes SET used = used + 1
                WHERE id = ? AND active = TRUE AND (max_uses = 0 OR used < max_uses)
                """, promo.getId());
        return updated > 0;
    }

    private static long discount(PromoCode p, long subtotal) {
        long d = p.getKind() == PromoCode.Kind.PERCENT
                ? Math.round(subtotal * (Math.min(100, p.getValue()) / 100.0))
                : p.getValue();
        return Math.max(0, Math.min(d, subtotal));
    }

    // ---- host management ----

    @Transactional(readOnly = true)
    public List<PromoCode> forOrganization(Long orgId) {
        return promos.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional
    public PromoCode create(Long orgId, Long eventId, String code, PromoCode.Kind kind,
                            long value, int maxUses, OffsetDateTime expiresAt) {
        PromoCode p = new PromoCode();
        p.setOrganizationId(orgId);
        p.setEventId(eventId);
        p.setCode(code.trim().toUpperCase());
        p.setKind(kind);
        p.setValue(kind == PromoCode.Kind.PERCENT ? Math.min(100, Math.max(1, value)) : Math.max(1, value));
        p.setMaxUses(Math.max(0, maxUses));
        p.setExpiresAt(expiresAt);
        return promos.save(p);
    }

    @Transactional
    public void setActive(Long orgId, Long promoId, boolean active) {
        promos.findById(promoId)
                .filter(p -> p.getOrganizationId().equals(orgId))
                .ifPresent(p -> { p.setActive(active); promos.save(p); });
    }
}
