-- Explicit owner-approved mapping, separate from schema migrations and synthetic CI provisioning.
BEGIN;
LOCK TABLE members IN EXCLUSIVE MODE;
DO $$
BEGIN
    IF (SELECT count(*) FROM members WHERE username IN ('anna', 'ben')) = 2
       AND NOT EXISTS (SELECT 1 FROM members WHERE username IN ('lisa', 'cedric')) THEN
        UPDATE members SET username = 'lisa', display_name = 'Lisa', version = version + 1 WHERE username = 'anna';
        UPDATE members SET username = 'cedric', display_name = 'Cedric', version = version + 1 WHERE username = 'ben';
    ELSIF (SELECT count(*) FROM members WHERE username IN ('lisa', 'cedric')) = 2
       AND NOT EXISTS (SELECT 1 FROM members WHERE username IN ('anna', 'ben')) THEN
        NULL; -- Already migrated; never create or delete identities.
    ELSE
        RAISE EXCEPTION 'Unexpected household identities; refusing ambiguous onboarding';
    END IF;
END $$;
COMMIT;
