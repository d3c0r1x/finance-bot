-- The notification worker may find active linked members and read only their schedule timezone.
-- All ordinary member access remains constrained by the existing tenant and subject policies.
CREATE POLICY notification_service_membership_lookup ON memberships
    FOR SELECT USING (current_setting('app.notification_service', true) = 'true');

CREATE POLICY notification_service_profile_lookup ON member_profiles
    FOR SELECT USING (current_setting('app.notification_service', true) = 'true');
