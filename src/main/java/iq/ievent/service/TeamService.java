package iq.ievent.service;

import iq.ievent.domain.Organization;
import iq.ievent.domain.TeamInvite;
import iq.ievent.domain.User;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.TeamInviteRepository;
import iq.ievent.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Organization team membership. Roles:
 *  OWNER   — the organization owner (implicit, from organizations.owner_user_id)
 *  MANAGER — everything except payment settings & team management
 *  STAFF   — attendees + check-in + read-only orders
 */
@Service
public class TeamService {

    public record Access(Organization org, String role) {
        public boolean owner() { return "OWNER".equals(role); }
        public boolean canManage() { return owner() || "MANAGER".equals(role); }
    }

    public record Member(long id, String fullName, String email, String role, String initials, String avatarUrl) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final TeamInviteRepository invites;
    private final MailService mail;
    private final JdbcTemplate jdbc;
    private final MessageSource messages;
    private final String baseUrl;

    public TeamService(OrganizationRepository organizations, UserRepository users, TeamInviteRepository invites,
                       MailService mail, JdbcTemplate jdbc, MessageSource messages,
                       @Value("${app.base-url}") String baseUrl) {
        this.organizations = organizations;
        this.users = users;
        this.invites = invites;
        this.mail = mail;
        this.jdbc = jdbc;
        this.messages = messages;
        this.baseUrl = baseUrl;
    }

    /** Localized user-facing message in the current request locale. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    /** Owner access first; otherwise membership access. */
    @Transactional(readOnly = true)
    public Optional<Access> accessOf(User user) {
        Optional<Organization> owned = organizations.findFirstByOwnerUserId(user.getId());
        if (owned.isPresent()) return Optional.of(new Access(owned.get(), "OWNER"));
        List<Access> memberships = jdbc.query("""
                SELECT m.organization_id, m.role FROM org_members m WHERE m.user_id = ?
                ORDER BY m.created_at ASC LIMIT 1
                """,
                (rs, i) -> {
                    Organization org = organizations.findById(rs.getLong(1)).orElse(null);
                    return org == null ? null : new Access(org, rs.getString(2));
                },
                user.getId());
        return memberships.stream().filter(a -> a != null).findFirst();
    }

    @Transactional(readOnly = true)
    public List<Member> members(Organization org) {
        List<Member> out = new java.util.ArrayList<>();
        users.findById(org.getOwnerUserId()).ifPresent(o ->
                out.add(new Member(0, o.getFullName(), o.getEmail(), "OWNER", o.initials(), o.getAvatarUrl())));
        out.addAll(jdbc.query("""
                SELECT m.id, u.full_name, u.email, m.role, u.avatar_url
                FROM org_members m JOIN users u ON u.id = m.user_id
                WHERE m.organization_id = ? ORDER BY m.created_at ASC
                """,
                (rs, i) -> new Member(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), initials(rs.getString(2)), rs.getString(5)),
                org.getId()));
        return out;
    }

    /** Result of an invite attempt: {@code pending} tells the controller which
     *  flash-message suffix to show ("added" vs "invited — check their email"). */
    public record InviteResult(String error, boolean pending) {
        static InviteResult ok(boolean pending) { return new InviteResult(null, pending); }
        static InviteResult error(String message) { return new InviteResult(message, false); }
    }

    /** Invites someone by email — whether or not they have an iEvent account
     *  yet. An existing user is added to org_members immediately and notified
     *  by email; anyone else gets a real invite email with a token link that
     *  prompts them to sign in/register before joining (see acceptInvite). */
    @Transactional
    public InviteResult invite(Organization org, String email, String role, User invitedBy, Locale locale) {
        String cleanEmail = email == null ? "" : email.trim();
        if (cleanEmail.isBlank()) return InviteResult.error(msg("team.invite.emailRequired"));
        String cleanRole = "MANAGER".equalsIgnoreCase(role) ? "MANAGER" : "STAFF";
        String roleLabel = messages.getMessage(
                "MANAGER".equals(cleanRole) ? "host.set.manager" : "host.set.doorStaff", null, LocaleContextHolder.getLocale());

        User target = users.findByEmailIgnoreCase(cleanEmail).orElse(null);
        if (target != null) {
            if (target.getId().equals(org.getOwnerUserId())) return InviteResult.error(msg("team.invite.alreadyOwner"));
            int inserted = jdbc.update("""
                    INSERT INTO org_members (organization_id, user_id, role) VALUES (?, ?, ?)
                    ON CONFLICT (organization_id, user_id) DO UPDATE SET role = EXCLUDED.role
                    """, org.getId(), target.getId(), cleanRole);
            if (inserted > 0 && target.getRole() == User.Role.USER) {
                target.setRole(User.Role.HOST);
                users.save(target);
            }
            mail.sendTeamAdded(target.getEmail(), org.getName(), roleLabel, locale);
            return InviteResult.ok(false);
        }

        TeamInvite invite = invites.findByOrganizationIdAndEmailIgnoreCaseAndAcceptedAtIsNull(org.getId(), cleanEmail)
                .orElseGet(TeamInvite::new);
        invite.setOrganization(org);
        invite.setEmail(cleanEmail);
        invite.setRole(cleanRole);
        invite.setToken(randomToken(40));
        invite.setInvitedByUserId(invitedBy == null ? null : invitedBy.getId());
        invite.setExpiresAt(OffsetDateTime.now().plusDays(7));
        invites.save(invite);
        mail.sendTeamInvite(cleanEmail, org.getName(), roleLabel, baseUrl + "/invite/" + invite.getToken(), locale);
        return InviteResult.ok(true);
    }

    @Transactional(readOnly = true)
    public List<TeamInvite> pendingInvites(Organization org) {
        return invites.findByOrganizationIdAndAcceptedAtIsNullOrderByCreatedAtDesc(org.getId()).stream()
                .filter(i -> i.getExpiresAt().isAfter(OffsetDateTime.now()))
                .toList();
    }

    @Transactional
    public void cancelInvite(Organization org, long inviteId) {
        invites.findById(inviteId)
                .filter(i -> i.getOrganization().getId().equals(org.getId()))
                .ifPresent(invites::delete);
    }

    /** Regenerates the token/expiry and re-sends the invite email. */
    @Transactional
    public String resendInvite(Organization org, long inviteId, Locale locale) {
        TeamInvite invite = invites.findById(inviteId)
                .filter(i -> i.getOrganization().getId().equals(org.getId()))
                .orElse(null);
        if (invite == null) return msg("team.invite.notFound");
        invite.setToken(randomToken(40));
        invite.setExpiresAt(OffsetDateTime.now().plusDays(7));
        invites.save(invite);
        String roleLabel = messages.getMessage(
                "MANAGER".equals(invite.getRole()) ? "host.set.manager" : "host.set.doorStaff", null, LocaleContextHolder.getLocale());
        mail.sendTeamInvite(invite.getEmail(), org.getName(), roleLabel, baseUrl + "/invite/" + invite.getToken(), locale);
        return null;
    }

    /** What the public /invite/{token} landing page needs to render — looked
     *  up regardless of whether the visitor is logged in yet. */
    public record InviteView(String orgName, String role, String email, boolean expired) {}

    @Transactional(readOnly = true)
    public Optional<InviteView> lookupInvite(String token) {
        return invites.findByToken(token)
                .filter(i -> i.getAcceptedAt() == null)
                .map(i -> new InviteView(i.getOrganization().getName(), i.getRole(), i.getEmail(),
                        i.getExpiresAt().isBefore(OffsetDateTime.now())));
    }

    /** Joins the invited org as the invited role. Requires the accepting
     *  user's email to match the invite (case-insensitively) — otherwise
     *  whoever happens to be logged in when the link is clicked could hijack
     *  someone else's invite. Returns an error message or null on success. */
    @Transactional
    public String acceptInvite(String token, User user) {
        TeamInvite invite = invites.findByToken(token).filter(i -> i.getAcceptedAt() == null).orElse(null);
        if (invite == null) return msg("team.invite.invalid");
        if (invite.getExpiresAt().isBefore(OffsetDateTime.now())) return msg("team.invite.expired");
        if (!invite.getEmail().equalsIgnoreCase(user.getEmail())) return msg("team.invite.wrongEmail", invite.getEmail());
        Organization org = invite.getOrganization();
        if (user.getId().equals(org.getOwnerUserId())) return msg("team.invite.alreadyOwner");
        jdbc.update("""
                INSERT INTO org_members (organization_id, user_id, role) VALUES (?, ?, ?)
                ON CONFLICT (organization_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """, org.getId(), user.getId(), invite.getRole());
        if (user.getRole() == User.Role.USER) {
            user.setRole(User.Role.HOST);
            users.save(user);
        }
        invite.setAcceptedAt(OffsetDateTime.now());
        invites.save(invite);
        return null;
    }

    private static String randomToken(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    @Transactional
    public void remove(Organization org, long memberId) {
        jdbc.update("DELETE FROM org_members WHERE id = ? AND organization_id = ?", memberId, org.getId());
    }

    private static String initials(String name) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
