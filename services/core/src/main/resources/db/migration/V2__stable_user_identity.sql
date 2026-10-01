CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    status varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'erasure_pending')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE external_identities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    provider varchar(32) NOT NULL,
    subject varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, subject),
    UNIQUE (provider, user_id)
);

-- V1 forces RLS. Suspend policies only for this owner-only backfill, then restore them.
ALTER TABLE memberships DISABLE ROW LEVEL SECURITY;
ALTER TABLE transactions DISABLE ROW LEVEL SECURITY;

WITH identity_map AS MATERIALIZED (
    SELECT subject, gen_random_uuid() AS user_id
    FROM (
        SELECT subject FROM memberships
        UNION
        SELECT owner_subject AS subject FROM transactions
    ) subjects
), created_users AS (
    INSERT INTO users (id)
    SELECT user_id FROM identity_map
    RETURNING id
)
INSERT INTO external_identities (user_id, provider, subject)
SELECT identity_map.user_id, 'keycloak', identity_map.subject
FROM identity_map
JOIN created_users ON created_users.id = identity_map.user_id;

ALTER TABLE memberships ADD COLUMN user_id uuid;
UPDATE memberships m SET user_id = i.user_id
FROM external_identities i WHERE i.provider = 'keycloak' AND i.subject = m.subject;
ALTER TABLE memberships ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE memberships ADD CONSTRAINT memberships_user_fk FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE memberships ADD CONSTRAINT memberships_tenant_user_uniq UNIQUE (tenant_id, user_id);

ALTER TABLE transactions ADD COLUMN owner_user_id uuid;
UPDATE transactions t SET owner_user_id = i.user_id
FROM external_identities i WHERE i.provider = 'keycloak' AND i.subject = t.owner_subject;
ALTER TABLE transactions ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE transactions ADD CONSTRAINT transactions_owner_user_fk FOREIGN KEY (owner_user_id) REFERENCES users(id);
CREATE INDEX transactions_tenant_user_history_idx
    ON transactions (tenant_id, owner_user_id, occurred_at DESC, id DESC);

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;
