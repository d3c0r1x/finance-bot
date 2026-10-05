# ADR-014: Private receipt image storage

**Status:** Accepted for implementation during autonomous completion of the approved project plan
**Date:** 2026-10-04

## Context

Receipt images contain personal and financial information. They must remain
private, must not be exposed through public bucket URLs, and must be processed
only after validation and malware scanning. The project needs a self-hosted
development option that speaks the same object-storage API as production.

## Decision

- Use SeaweedFS's S3-compatible API for the self-hosted development profile.
  SeaweedFS is Apache-2.0 licensed and documents an S3-compatible server. The
  MinIO upstream repository is archived and identifies its license as AGPLv3,
  so MinIO is not selected for this project.
- Use the AWS SDK for Java 2.x S3 client behind a small `ObjectStorage` interface.
  The adapter accepts an endpoint, region, bucket and credentials from runtime
  configuration; production may use AWS S3 or another compatible service.
- Tests use a private filesystem adapter with path-safe generated keys. The
  filesystem adapter is for tests and local development only; it is not a
  production object-store replacement.
- Store uploaded files in a private quarantine prefix. Only the Core service
  and receipt worker can read them. Browser access always passes through an
  authenticated, owner-scoped API; this feature does not return public or
  pre-signed URLs. S3-compatible buckets must deny anonymous/public access at
  the provider policy level; object metadata is not an access-control mechanism.
- Require a malware scan before OCR or document-ready state. An unavailable
  scanner leaves the job retryable and the object quarantined.

## Consequences

SeaweedFS and ClamAV belong in the development/full profiles and integration
tests. Storage keys are random and tenant-scoped; filenames never become paths
or object keys. A later provider change is limited to the adapter/configuration
boundary and does not alter database document IDs or API contracts.

## References

- [SeaweedFS project and license](https://github.com/seaweedfs/seaweedfs)
- [MinIO archived upstream repository and license](https://github.com/minio/minio)
- [AWS SDK for Java 2.x S3 client](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)
