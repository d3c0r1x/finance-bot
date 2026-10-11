# F50 Android merchant classification

1. Add strict Android DTO parsing for category provenance, Core hypothesis,
   confidence, and Core-selected clarification merchants.
2. Add authenticated, revision-guarded Core classify and row-category API calls.
3. Add RU/EN import-screen controls for explicit classification and personal
   merchant mapping; keep Core responses authoritative and F51 out of scope.
4. Run parser, API, and Compose tests on the isolated API 27 emulator; repeat
   F47-F49 import regression, build APKs, inspect the final diff, and update the
   feature registry and durable progress log.
