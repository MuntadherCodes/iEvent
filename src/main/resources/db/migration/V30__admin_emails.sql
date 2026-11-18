-- R44: emails the super-admin writes from the console (/admin/emails) to an organizer, a
-- buyer or any other address. These are new messages, not answers to a contact-form
-- message: those stay in inquiry_replies (V29).
--
-- One row per recipient, written only after the mail server accepted the message, so
-- the list in the console never shows an email that did not go out.
--
-- to_name  the name typed for the greeting ("Hello Ali,"); NULL when none was given or
--          when the same email went to several addresses
-- lang     the language the frame of the mail was laid out in ('ar' or 'en'): Arabic
--          when the subject or the message holds Arabic letters, English otherwise
CREATE TABLE admin_emails (
    id       BIGSERIAL PRIMARY KEY,
    to_email VARCHAR(255) NOT NULL,
    to_name  VARCHAR(120),
    subject  VARCHAR(200) NOT NULL,
    body     TEXT         NOT NULL,
    lang     VARCHAR(5)   NOT NULL DEFAULT 'ar',
    sent_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_emails_sent ON admin_emails (sent_at DESC);
