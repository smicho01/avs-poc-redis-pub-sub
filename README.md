# AVS Service - Redis Pub/Sub Flow

## Overview

AVS (Antivirus Service) bridges a synchronous HTTP request from file-api with an
asynchronous scan result from the External Malware Scanner. Redis Pub/Sub is the
mechanism that connects these two worlds across multiple pods.

---

## Single AVS Instance Flow

### Step 1 - file-api calls AVS

```
Postman
  │ GET /v1/files/{fileId}/content
  ▼
file-api
  │ loads file metadata from Postgres
  │ calls AVS  →  GET http://avs-service:8081/scan?s3Bucket=...&s3Key=...&fileName=...
  ▼
AVS ScanController
  │ generates correlationId = "abc-123"
  │ creates DeferredResult
  │ registers it: pendingScans.put("abc-123", deferredResult)
  │ copies file to external-malware-scanner-inbox
  │ returns DeferredResult  ← Tomcat thread released here
  │
  │ ... HTTP connection stays open, client is waiting ...
```

The Tomcat thread is released immediately after registering the DeferredResult.
The HTTP connection to file-api stays open but no thread is blocked waiting for it.

---

### Step 2 - External Malware Scanner processes the file

```
emss-simulator
  │ polls external-malware-scanner-inbox every 2 seconds
  │ finds file with key "abc-123/fileId/filename.jpg"
  │ extracts correlationId from key = "abc-123"
  │ checks if filename contains "eicar"
  │   → no  : ScanResultPayload(correlationId, clean=true,  code=200,  message="File is clean")
  │   → yes : ScanResultPayload(correlationId, clean=false, code=4231, message="File is infected")
  │ publishes payload to SNS topic "external-malware-scanner-outcome"
  │ deletes file from inbox
  ▼
SNS delivers message to SQS queue "scan-results"
```

---

### Step 3 - AVS SQS consumer receives the result

```
AVS SqsConsumer
  │ receives raw SQS message
  │ unwraps SNS envelope (SNS wraps payload in a "Notification" JSON)
  │ deserializes to ScanResultPayload with correlationId="abc-123"
  │ publishes to Redis channel "scan:result:abc-123"
```

---

### Step 4 - Redis delivers to the waiting pod

```
Redis
  │ receives message on channel "scan:result:abc-123"
  │ pushes to all subscribers of pattern "scan:result:*"
  ▼
AVS RedisSubscriber
  │ receives message
  │ deserializes to ScanResultPayload
  │ calls registry.complete("abc-123", result)
  │
PendingScanRegistry
  │ pendingScans.get("abc-123")  → found DeferredResult
  │ deferredResult.setResult(result)
  │
Spring MVC
  │ DeferredResult completed
  │ writes HTTP response back to file-api
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

## Full Flow Diagram

```
Postman
  │ GET /v1/files/{fileId}/content
  ▼
file-api  ──────────────────────────────────────────────────────────────────┐
  │ GET /scan?s3Bucket=...&s3Key=...&fileName=...                           │
  ▼                                                                         │
AVS ScanController                                                          │
  │ correlationId = "abc-123"                                               │
  │ pendingScans.put("abc-123", deferredResult)                             │
  │ copy file → external-malware-scanner-inbox                              │
  │ Tomcat thread released                                                  │
  │                                                                         │
  │ ... waiting ...                                                         │
  ▼                                                                         │
emss-simulator                                                              │
  │ polls inbox, finds file                                                 │
  │ publishes result to SNS                                                 │
  ▼                                                                         │
SNS → SQS                                                                   │
  ▼                                                                         │
AVS SqsConsumer                                                             │
  │ publishes to Redis "scan:result:abc-123"                                │
  ▼                                                                         │
Redis → AVS RedisSubscriber                                                 │
  │ registry.complete("abc-123", result)                                    │
  │ deferredResult.setResult(result)                                        │
  ▼                                                                         │
Spring MVC sends HTTP response ─────────────────────────────────────────▶  │
  ▼                                                                         │
file-api                                                                    │
  │ clean  → stream file from S3 ──────────────────────────────────────────┘
  │ infected → 423 Locked
  ▼
Postman receives response
```

---

## Key Design Decisions

**DeferredResult** releases the Tomcat thread immediately after registering the scan.
The HTTP connection stays open but no thread is consumed while waiting for the scan result.
This allows AVS to handle thousands of concurrent scans with a small thread pool.

**ConcurrentHashMap** holds correlationId to DeferredResult mappings safely across
multiple threads. One thread registers the entry (HTTP request thread), a different
thread completes it (Redis listener thread).

**Redis Pub/Sub** uses a wildcard pattern `scan:result:*` so a single subscription
covers all scan result channels. No subscribe/unsubscribe per request is needed.

**correlationId encoded in S3 key** as `{correlationId}/{originalKey}` allows
emss-simulator to extract the correlationId without needing a database or metadata lookup.

**SNS envelope unwrapping** is handled in SqsConsumer because SNS wraps the payload
in a Notification envelope when delivering to SQS. The actual payload sits inside
the `Message` field as an escaped JSON string.