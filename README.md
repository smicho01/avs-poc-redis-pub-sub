# AVS Service - Redis Pub/Sub with Scan Result Cache

## Overview

AVS (Antivirus Service) bridges a synchronous HTTP request from file-api with an
asynchronous scan result from the External Malware Scanner. Redis serves two roles:

- **Pub/Sub** - delivers scan results across AVS pods in real time
- **Cache** - stores recent scan results so repeat requests skip the external scanner entirely

---

## Redis Key Patterns

Two completely separate key patterns are used in Redis:

```
scan:result:{correlationId}   Pub/Sub channel - exists for milliseconds only
scan:cache:{fileId}           Cache key       - exists for 120 seconds (configurable)
```

---

## Full Flow - Cache Miss (first request for a file)

### Step 1 - file-api calls AVS

```
Postman
  │ GET /v1/files/{fileId}/content
  ▼
file-api
  │ loads file metadata from Postgres (bucket, key, filename, mimeType)
  │ calls AVS → GET http://avs-service:8081/scan
  │             ?s3Bucket=...&s3Key=...&fileName=...&fileId=...
  ▼
AVS ScanController
  │ checks Redis cache: scan:cache:{fileId}
  │ → MISS (file not scanned recently)
  │
  │ generates correlationId = "abc-123"
  │ creates DeferredResult with 30 second timeout
  │ registers in PendingScanRegistry:
  │     pendingResults.put("abc-123", deferredResult)
  │     correlationToFileId.put("abc-123", fileId)
  │
  │ copies file to external-malware-scanner-inbox
  │ returns DeferredResult ← Tomcat thread released here
  │
  │ ... HTTP connection stays open, client is waiting ...
```

---

### Step 2 - External Malware Scanner processes the file

```
emss-simulator
  │ polls external-malware-scanner-inbox every 2 seconds
  │ finds file with key "{correlationId}/{fileId}/{filename}"
  │ extracts correlationId from key = "abc-123"
  │ checks if filename contains "eicar":
  │   → no  : ScanResultPayload(correlationId, clean=true,  code=200,  "File is clean")
  │   → yes : ScanResultPayload(correlationId, clean=false, code=4231, "File is infected")
  │ publishes payload to SNS topic "external-malware-scanner-outcome"
  │ copies file to healthy or quarantine bucket
  │ deletes file from inbox
  ▼
SNS delivers message to SQS queue "scan-results"
```

---

### Step 3 - AVS SQS consumer receives the result

```
AVS SqsConsumer (any pod)
  │ receives raw SQS message
  │ unwraps SNS envelope
  │     SNS wraps payload in a "Notification" JSON
  │     actual payload sits inside "Message" field as escaped JSON string
  │ deserializes inner payload to ScanResultPayload with correlationId="abc-123"
  │ publishes to Redis Pub/Sub channel "scan:result:abc-123"
```

---

### Step 4 - Redis delivers to the waiting pod

```
Redis
  │ receives message on channel "scan:result:abc-123"
  │ pushes to ALL pods subscribed to pattern "scan:result:*"
  ▼
AVS RedisSubscriber (fires on every pod simultaneously)
  │ deserializes message to ScanResultPayload
  │ calls registry.complete("abc-123", result)
  │
PendingScanRegistry.complete()
  │ pendingResults.remove("abc-123")   → DeferredResult (found on the pod that owns it)
  │ correlationToFileId.remove("abc-123") → fileId
  │
  │ writes result to Redis cache:
  │     scan:cache:{fileId} = {"clean":true,"code":200,...}  TTL: 120 seconds
  │
  │ deferredResult.setResult(result)   → wakes up waiting HTTP request
  │
  │ (on pods that don't own "abc-123", both removes return null → no-op)
  ▼
Spring MVC sends HTTP response back to file-api
```

---

### Step 5 - file-api responds to Postman

```
file-api receives ScanResult
  │
  ├── clean=true  → streams file bytes from S3
  │                 Content-Type from file metadata
  │                 HTTP 200
  │
  └── clean=false → HTTP 423 Locked
                    {
                      "status": "INFECTED",
                      "code": 4231,
                      "message": "File is infected"
                    }
```

---

## Full Flow - Cache Hit (repeat request within 120 seconds)

```
Postman
  │ GET /v1/files/{fileId}/content  (same file, within 2 minutes)
  ▼
file-api → AVS ScanController
  │ checks Redis cache: scan:cache:{fileId}
  │ → HIT (result found, not yet expired)
  │
  │ creates DeferredResult
  │ calls deferredResult.setResult(cachedResult) immediately
  │ returns
  │
  │ External scanner never involved
  │ No SQS, no SNS, no S3 copy
  │ Response time: ~1-5ms
  ▼
file-api responds to Postman instantly
```

---

## Complete Flow Diagram

```
                    ┌─────────────────────────────────────────────────┐
                    │              CACHE HIT PATH                      │
                    │   scan:cache:{fileId} exists in Redis            │
                    │   → return instantly, skip everything below      │
                    └─────────────────────────────────────────────────┘
                                        │
Postman                                 │ (miss)
  │ GET /v1/files/{fileId}/content      │
  ▼                                     ▼
file-api ──── GET /scan?...&fileId= ──▶ AVS ScanController
                                          │ correlationId = "abc-123"
                                          │ register(correlationId, fileId, deferredResult)
                                          │ copy file → external-malware-scanner-inbox
                                          │ Tomcat thread released
                                          │
                                          │ ... waiting ...
                                          ▼
                                    emss-simulator
                                          │ polls inbox, finds file
                                          │ publishes result to SNS
                                          ▼
                                      SNS → SQS
                                          ▼
                                    AVS SqsConsumer (any pod)
                                          │ publishes to Redis "scan:result:abc-123"
                                          ▼
                                Redis broadcasts to ALL pods
                                          │
                              ┌───────────┴───────────┐
                            Pod A                    Pod B
                              │                        │
                        found "abc-123"          not found → no-op
                              │
                        write scan:cache:{fileId} TTL 120s
                        deferredResult.setResult()
                              │
                    Spring MVC sends response
                              │
                        file-api responds
                              │
                    Postman receives file or 423
```

---

## Class Responsibilities

| Class | Responsibility |
|---|---|
| `ScanController` | Checks cache, creates DeferredResult, triggers external scan on miss |
| `ScanCacheService` | Reads and writes scan results to Redis with TTL |
| `PendingScanRegistry` | Holds correlationId → DeferredResult and correlationId → fileId mappings |
| `RedisSubscriber` | Receives Redis Pub/Sub messages, calls registry.complete() |
| `SqsConsumer` | Receives SQS messages, unwraps SNS envelope, publishes to Redis |
| `AmssUploadService` | Copies files from file-storage to external-malware-scanner-inbox |

---

## Key Design Decisions

**DeferredResult** releases the Tomcat thread immediately after registering the scan.
The HTTP connection stays open but no thread is consumed while waiting for the result.
This allows AVS to handle thousands of concurrent scans with a small thread pool.

**Two ConcurrentHashMaps in PendingScanRegistry** handle concurrent thread access safely.
One thread registers entries (HTTP request thread), a different thread completes them
(Redis listener thread). ConcurrentHashMap makes this safe without manual locking.

**Cache written on result arrival** not on request. The SQS consumer path writes to cache
via registry.complete(). Timeouts are never cached - the next request triggers a fresh scan.

**Redis Pub/Sub wildcard pattern** `scan:result:*` means each pod subscribes once at startup
and covers all scan result channels. No per-request subscribe/unsubscribe needed.

**correlationId encoded in S3 key** as `{correlationId}/{originalKey}` allows emss-simulator
to extract the correlationId without a database lookup.

**SNS envelope unwrapping** is handled explicitly in SqsConsumer because SNS wraps payloads
in a Notification envelope when delivering to SQS.

**Cache TTL of 120 seconds** means a file scanned less than 2 minutes ago returns instantly.
After expiry the next request triggers a fresh scan. Configurable via `scan.cache-ttl-seconds`.