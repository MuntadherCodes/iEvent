-- R38 #2: the number buyers see next to the own-risk note at checkout (R36) and on a
-- pending transfer order is set by the organizer on Settings > Payments. Stored in
-- E.164 (+9647XXXXXXXX, an Iraqi mobile). NULL means "fall back": the public contact
-- phone from Branding, then the owner's account number, then no number at all.
ALTER TABLE organizations ADD COLUMN payment_phone VARCHAR(32);
