package iq.ievent.service;

import iq.ievent.domain.Organization;
import iq.ievent.domain.User;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public record Member(long id, String fullName, String email, String role, String initials) {}

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public TeamService(OrganizationRepository organizations, UserRepository users, JdbcTemplate jdbc) {
        this.organizations = organizations;
        this.users = users;
        this.jdbc = jdbc;
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
                out.add(new Member(0, o.getFullName(), o.getEmail(), "OWNER", o.initials())));
        out.addAll(jdbc.query("""
                SELECT m.id, u.full_name, u.email, m.role
                FROM org_members m JOIN users u ON u.id = m.user_id
                WHERE m.organization_id = ? ORDER BY m.created_at ASC
                """,
                (rs, i) -> new Member(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), initials(rs.getString(2))),
                org.getId()));
        return out;
    }

    /** Invites an EXISTING registered user by email. Returns an error message or null on success. */
    @Transactional
    public String invite(Organization org, String email, String role) {
        User target = users.findByEmailIgnoreCase(email == null ? "" : email.trim()).orElse(null);
        if (target == null) {
            return "No iEvent account exists for " + email + " — ask them to register first, then invite again.";
        }
        if (target.getId().equals(org.getOwnerUserId())) {
            return "That user is already the owner of this organization.";
        }
        String cleanRole = "MANAGER".equalsIgnoreCase(role) ? "MANAGER" : "STAFF";
        int inserted = jdbc.update("""
                INSERT INTO org_members (organization_id, user_id, role) VALUES (?, ?, ?)
                ON CONFLICT (organization_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """, org.getId(), target.getId(), cleanRole);
        if (inserted > 0 && target.getRole() == User.Role.USER) {
            target.setRole(User.Role.HOST);
            users.save(target);
        }
        return null;
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
