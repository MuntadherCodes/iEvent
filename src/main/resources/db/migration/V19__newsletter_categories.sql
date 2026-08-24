-- Interest data captured by the home page's multi-step newsletter signup
-- (category chips + city), so the marketing list can be segmented.
ALTER TABLE newsletter_subscribers ADD COLUMN categories varchar(200);
