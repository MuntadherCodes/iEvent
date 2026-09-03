-- QA round: two fast clicks on the onboarding "Create" button raced past the
-- organizationOf() check and produced two organizations for one owner. The
-- controller now also catches the unique violation; this index is the real
-- guarantee. Guarded so a database that already holds a duplicate keeps
-- starting. In that case the index is NOT created (a versioned migration runs
-- once): merge or delete the duplicate organization by hand, then run
--   CREATE UNIQUE INDEX ux_organizations_owner ON organizations (owner_user_id);
-- yourself. Check with:
--   SELECT owner_user_id, COUNT(*) FROM organizations GROUP BY 1 HAVING COUNT(*) > 1;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM organizations GROUP BY owner_user_id HAVING COUNT(*) > 1) THEN
        RAISE NOTICE 'iEvent V26: duplicate organizations per owner exist; ux_organizations_owner NOT created, see migration comment';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_organizations_owner ON organizations (owner_user_id);
    END IF;
END $$;
