-- iEvent V11: store the Google account photo URL, set on Google sign-in and
-- refreshed on later logins. NULL for local accounts / Google accounts with no
-- photo — templates fall back to initials in that case.

ALTER TABLE users
    ADD COLUMN avatar_url VARCHAR(500);
