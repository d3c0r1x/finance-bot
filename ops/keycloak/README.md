# Local Keycloak realm

Import `realm-finance.json` into a local Keycloak 26.7.x development server.
It configures email login, registration, password recovery, brute-force
protection, short-lived access tokens, a confidential web BFF client, a public
Android PKCE client and the `finance-api` access-token audience.

`finance-local-dev-secret-not-for-production` is a throwaway local credential.
Replace the web client secret through the deployment secret store before using a
non-development Keycloak. Do not enable local `start-dev` for production.

For local browser testing, register a user through the Keycloak login screen,
verify the address against the configured mail provider, then open the web app.
The web app receives only a server session cookie; it never stores OIDC tokens in
browser storage.
