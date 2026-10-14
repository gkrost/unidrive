# S3 provider

A bucket on Amazon S3 or an S3-compatible service (Scaleway, Hetzner, Backblaze B2, Wasabi, OVH, MinIO).

The provider talks to the S3 REST API path-style (`<endpoint>/<bucket>/<key>`) over Ktor, through `S3Api`, and does without the AWS SDK. `SigV4Signer` signs every request. It was written from AWS's published Signature Version 4 algorithm, and its tests are AWS's published worked examples. Behaviour notes taken from Cyberduck are behaviour only. Cyberduck is GPL-3.0, and no code was taken from it.

## Profile

```toml
[providers.s3]
type = "s3"
sync_root = "~/S3"
bucket = "my-bucket"
region = "fr-par"                       # default us-east-1
endpoint = "https://s3.fr-par.scw.cloud" # default: AWS in that region
access_key_id = "..."
secret_access_key = "..."
```

`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `S3_BUCKET` fill the same keys from the environment.

## Vendors

What differs between vendors is read off the endpoint's host (`S3Config.Vendor`):

| Host | Parts per multipart upload | Conditional write |
|---|---|---|
| `*.amazonaws.com` | 10,000 | `If-Match` on PUT and CompleteMultipartUpload: a 412 is a conflict |
| `*.scw.cloud` | 1,000 (Scaleway's documented limit) | a HEAD comparison first |
| any other | 10,000 | a HEAD comparison first |

## API surface used

| Request | Used by | Notes |
|---|---|---|
| `GET ?list-type=2` (ListObjectsV2) | `delta`, `listChildren`, folder checks | `delta` lists the whole bucket (`deltaIsFullListing`), and the engine finds deletions by absence. A listing cut off half-way resumes with `start-after` the last key the scan context kept. `listChildren` uses the `/` delimiter. |
| `HEAD` | `getMetadata`, the write guard, after every upload | |
| `GET` | `download` | The bytes go to a staging sibling, moved over the target only when complete. A 404 is `PermanentDownloadFailureException`. |
| `PUT` | `upload` up to 100 MiB, folder markers (`key/`) | `x-amz-meta-mtime` carries the file's mtime as rclone writes it (seconds since the epoch, with a fraction). |
| `POST ?uploads`, `PUT ?partNumber`, `POST ?uploadId`, `DELETE ?uploadId`, `GET ?uploadId` | multipart above 100 MiB | Parts are at least 16 MiB, sized to the vendor's part limit, streamed from the file and checked with `Content-MD5`. The open upload is kept under `s3-uploads/` in the profile folder. A second attempt on the unchanged file reuses the parts S3 holds (ListParts, compared by MD5). `discardStagedUpload` aborts it. |
| `PUT` + `x-amz-copy-source` | `move` up to 5 GiB | CopyObject with `x-amz-metadata-directive: COPY`, then DELETE the source. |
| `PUT ?partNumber` + `x-amz-copy-source-range` | `move` above 5 GiB | UploadPartCopy on every vendor, in parts of at least 512 MiB. |
| `DELETE` | `delete` of a file | A missing key counts as deleted. |
| `POST ?delete` (DeleteObjects) | `delete` of a folder, folder moves | 1,000 keys per call with `Content-MD5`. A vendor that refuses it gets one DELETE per key. |
| presigned `GET` | `share` | Seven days at most, and no password. |

Every request takes a slot from the shared `HttpRetryBudget`. A 500, 503 (SlowDown) or 429 is retried with backoff. A 401 or 403 is `AuthenticationException`, and a 412 is `RemoteConflictException`.

## Hash and time

- **Hash:** `HashAlgorithm.Md5Hex` on the ETag. A multipart ETag (`<hex>-<n>`) is not an MD5, and verification skips it. Neither is an SSE-KMS or SSE-C ETag, which the listing cannot tell apart.
- **Time:** an item's `modified` is S3's `LastModified` wherever the item comes from. A listing carries no user metadata, and the engine compares the time it recorded, so the time read from a listing and the one read after an upload must be the same. The provider writes `x-amz-meta-mtime` but does not read it back yet.

## Not yet

- **Hermetic tests on MinIO:** a Testcontainer suite, plus opt-in runs on AWS and Scaleway. The tests here use an in-memory fake S3.
- **Reading back `x-amz-meta-mtime`:** a HEAD per object, or an engine-side decision on which time to compare.
- **Quota:** none (`hasQuota = false`); a bucket has no plan to report.
