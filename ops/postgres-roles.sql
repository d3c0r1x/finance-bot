-- Run while connected to the target `finance` database as its administrator.
-- Configure authentication through the environment's secret/identity provider.
-- Do not add role passwords to this file or to source control.
CREATE ROLE finance_migrator LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;
CREATE ROLE finance_app LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;

GRANT CONNECT ON DATABASE finance TO finance_migrator, finance_app;
GRANT USAGE, CREATE ON SCHEMA public TO finance_migrator;
GRANT USAGE ON SCHEMA public TO finance_app;

ALTER DEFAULT PRIVILEGES FOR ROLE finance_migrator IN SCHEMA public
    GRANT SELECT, INSERT ON TABLES TO finance_app;
ALTER DEFAULT PRIVILEGES FOR ROLE finance_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO finance_app;
