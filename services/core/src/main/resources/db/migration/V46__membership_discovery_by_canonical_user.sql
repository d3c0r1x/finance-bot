-- An OIDC identity attached to a Telegram-native account must discover the
-- existing tenant through the immutable canonical user ID.
DROP POLICY tenant_isolation ON memberships;
CREATE POLICY tenant_isolation ON memberships
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
           OR subject = nullif(current_setting('app.subject', true), '')
           OR user_id = nullif(current_setting('app.user_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
