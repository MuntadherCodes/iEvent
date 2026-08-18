-- iEvent V8: remember each user's preferred language so buyer-directed emails
-- and in-app notifications localize to the RECIPIENT, not the actor.
-- NULL = never expressed a preference (treated as Arabic, the site default).

ALTER TABLE users
    ADD COLUMN preferred_lang VARCHAR(5);
