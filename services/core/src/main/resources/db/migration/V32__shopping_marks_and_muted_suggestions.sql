CREATE TABLE shopping_marks (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    product_key varchar(256) NOT NULL CHECK (product_key ~ '^[a-zа-я0-9]{1,256}$'),
    marked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, product_key),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE
);

CREATE INDEX shopping_marks_member_idx ON shopping_marks (tenant_id, user_id, marked_at DESC);

ALTER TABLE shopping_marks ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopping_marks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopping_marks
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE muted_suggestions (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    section varchar(32) NOT NULL CHECK (section IN ('shopping', 'recurring')),
    suggestion_key varchar(256) NOT NULL CHECK (length(suggestion_key) BETWEEN 1 AND 256),
    muted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, section, suggestion_key),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE
);

CREATE INDEX muted_suggestions_member_idx
    ON muted_suggestions (tenant_id, user_id, section, muted_at DESC);

ALTER TABLE muted_suggestions ENABLE ROW LEVEL SECURITY;
ALTER TABLE muted_suggestions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON muted_suggestions
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
