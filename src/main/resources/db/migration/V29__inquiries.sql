-- R42: messages sent through the website's contact form (/contact). Until now they were
-- only forwarded to the support mailbox; they are now also kept here, so the super-admin
-- console can list them with the time they arrived, show which ones still wait for an
-- answer, and send the answer by email.
--
-- lang       language the visitor was browsing in ('ar' or 'en'); the reply email is
--            laid out in it
-- user_id    the signed-in account the message came from, when there was one
-- replied_at set when a reply is sent from the console, or when an admin marks the
--            inquiry as replied by hand (answered from the mailbox, by phone, or
--            needing no answer); NULL means it still waits
CREATE TABLE inquiries (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    topic      VARCHAR(20)  NOT NULL DEFAULT 'general',
    message    TEXT         NOT NULL,
    lang       VARCHAR(5)   NOT NULL DEFAULT 'ar',
    user_id    BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    replied_at TIMESTAMPTZ
);
CREATE INDEX ix_inquiries_created ON inquiries (created_at DESC);

-- Every reply sent from the console, oldest first on the inquiry's page. An inquiry
-- marked as replied by hand has replied_at set and no rows here.
CREATE TABLE inquiry_replies (
    id         BIGSERIAL PRIMARY KEY,
    inquiry_id BIGINT      NOT NULL REFERENCES inquiries (id) ON DELETE CASCADE,
    body       TEXT        NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_inquiry_replies_inquiry ON inquiry_replies (inquiry_id, sent_at);
