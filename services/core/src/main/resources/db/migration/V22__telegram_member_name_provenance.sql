ALTER TABLE member_profiles
    ADD COLUMN display_name_source varchar(24) NOT NULL DEFAULT 'user'
        CHECK (display_name_source IN ('user', 'workspace_default', 'telegram'));

CREATE POLICY telegram_link_membership_lookup ON memberships
    FOR SELECT USING (
        current_setting('app.telegram_link_service', true) = 'true'
        AND user_id = nullif(current_setting('app.telegram_link_user_id', true), '')::uuid
    );
