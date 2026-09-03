-- R31 #8: free-text instructions for online events (meeting password, how to
-- join, what to prepare). Shown ONLY to confirmed ticket holders, next to the
-- join link, on the confirmation page, My tickets and the confirmed email.
ALTER TABLE events ADD COLUMN online_instructions TEXT;
