# Apigee Shared Flows: API Request Signing & Signature Validation

Two production-ready Apigee **Shared Flows** for OAuth token management and API request signing/validation, using exclusively native Apigee policies and JavaScript policies where native options are insufficient.

| Shared Flow | Purpose |
|-------------|---------|
| **`api-sign-token`** | Acquire and cache OAuth tokens from an external IDP via mTLS |
| **`api-sign-request`** | Sign outbound requests and validate inbound request signatures |

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│              Shared Flow 1: api-sign-token                       │
│                                                                  │
│  KVM-ReadSignConfig ──► AM-SetKvmLoaded ──► LookupCache          │
│       (guarded)           (guarded)          AccessToken          │
│                                                  │               │
│                              ┌───── cache hit ───┘               │
│                              │                                   │
│                     cache miss ▼                                 │
│              AM-BuildTokenRequest ──► SC-FetchToken (ts-idp)     │
│              ──► EV-ExtractToken ──► PopulateCache-AccessToken   │
│                              │                                   │
│                              ▼                                   │
│                     AM-SetAccessToken ──► RF-TokenFetchFailed    │
│                                            (if token is null)    │
└──────────────────────────────────────────────────────────────────┘

         sign.accessToken is available for downstream policies

┌──────────────────────────────────────────────────────────────────┐
│              Shared Flow 2: api-sign-request                     │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ hook-sign-request                                          │  │
│  │                                                            │  │
│  │  KVM-ReadSignConfig ──► AM-SetKvmLoaded                    │  │
│  │       (guarded)           (guarded)                        │  │
│  │          │                                                 │  │
│  │          ▼                                                 │  │
│  │  JS-BuildSigningString (canonicalize + SHA-256 + JOSE)     │  │
│  │          │                                                 │  │
│  │          ▼                                                 │  │
│  │  AM-BuildSignRequest ──► SC-CallSignService                │  │
│  │  ──► EV-ExtractSignature ──► AM-AttachSignature            │  │
│  │          │                                                 │  │
│  │          ▼                                                 │  │
│  │  RF-SignFailed (if signature is null)                       │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ hook-validate-request                                      │  │
│  │                                                            │  │
│  │  KVM-ReadSignConfig ──► AM-SetKvmLoaded                    │  │
│  │       (guarded)           (guarded)                        │  │
│  │          │                                                 │  │
│  │          ▼                                                 │  │
│  │  JS-ExtractIncomingSignature (dynamic header extraction)   │  │
│  │  ──► JS-BuildValidateDigest (canonicalize + SHA-256)       │  │
│  │          │                                                 │  │
│  │          ▼                                                 │  │
│  │  AM-BuildValidateRequest ──► SC-CallValidateService        │  │
│  │  ──► EV-ExtractValidationResult                            │  │
│  │          │                                                 │  │
│  │          ▼                                                 │  │
│  │  RF-ValidationFailed (if result != "true")                 │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

---

## Prerequisites

- Apigee Edge OPDK environment
- Management API access with org admin or environment admin permissions
- mTLS certificates configured in Apigee keystores/truststores

---

## 1. TargetServer Setup

### Create `ts-idp`

```bash
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/targetservers" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{
    "name": "ts-idp",
    "host": "{idp-hostname}",
    "port": 443,
    "isEnabled": true,
    "sSLInfo": {
      "enabled": true,
      "clientAuthEnabled": true,
      "keyStore": "{env-keystore-name}",
      "keyAlias": "{env-key-alias}",
      "trustStore": "{env-truststore-name}",
      "protocols": [],
      "ciphers": []
    }
  }'
```

### Create `ts-signature-service`

```bash
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/targetservers" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{
    "name": "ts-signature-service",
    "host": "{signature-service-hostname}",
    "port": 443,
    "isEnabled": true,
    "sSLInfo": {
      "enabled": true,
      "clientAuthEnabled": true,
      "keyStore": "{env-keystore-name}",
      "keyAlias": "{env-key-alias}",
      "trustStore": "{env-truststore-name}",
      "protocols": [],
      "ciphers": []
    }
  }'
```

> Replace `{management-host}`, `{org}`, `{env}`, `{idp-hostname}`, `{signature-service-hostname}`,
> `{env-keystore-name}`, `{env-key-alias}`, and `{env-truststore-name}` with your environment values.

---

## 2. Cache Resource Setup

Create the `sign-token-cache` cache resource:

```bash
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/caches" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{
    "name": "sign-token-cache",
    "description": "Cache for OAuth access tokens used by API signing shared flows",
    "expirySettings": {
      "timeoutInSec": { "value": "3600" },
      "valuesNull": false
    },
    "skipCacheIfElementSizeInKBExceeds": "512"
  }'
```

---

## 3. KVM Population

Both shared flows read from the same encrypted KVM `api-sign-config` (environment-scoped). Each shared flow reads only the keys it needs.

### Create KVM

```bash
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{
    "name": "api-sign-config",
    "encrypted": true
  }'
```

### Populate KVM Entries

```bash
# === Token shared flow keys ===

# IDP token endpoint path
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "idp.token.path", "value": "/oauth2/token"}'

# IDP client ID
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "idp.client.id", "value": "{your-client-id}"}'

# IDP client secret
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "idp.client.secret", "value": "{your-client-secret}"}'

# IDP OAuth scope
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "idp.scope", "value": "signing"}'

# Token cache TTL (seconds)
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "idp.token.cache.ttl.secs", "value": "3500"}'

# === Signature shared flow keys ===

# Signature service sign path
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "signature.service.path.sign", "value": "/sign"}'

# Signature service validate path
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "signature.service.path.validate", "value": "/validate"}'

# Signature header name
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "signature.header.name", "value": "X-Request-Signature"}'

# Request components (JSON array)
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "request.components", "value": "[{\"name\":\"method\",\"source\":\"flow\",\"value\":\"request.verb\"},{\"name\":\"path\",\"source\":\"flow\",\"value\":\"request.uri\"},{\"name\":\"body\",\"source\":\"body\",\"value\":\"\"}]"}'

# JOSE headers (JSON array)
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/keyvaluemaps/api-sign-config/entries" \
  -H "Content-Type: application/json" \
  -u "{admin-email}:{password}" \
  -d '{"name": "jose.headers", "value": "[{\"name\":\"alg\",\"value\":\"flow:sign.in.algorithm\",\"critical\":true,\"source\":\"flow\"},{\"name\":\"kid\",\"value\":\"flow:sign.in.keyId\",\"critical\":true,\"source\":\"flow\"},{\"name\":\"typ\",\"value\":\"JWT\",\"critical\":false,\"source\":\"static\"}]"}'
```

---

## 4. Shared Flow Deployment

Both shared flows must be deployed independently.

### Package & Deploy `api-sign-token`

```bash
cd apigee-api-sign-validate/api-sign-token
zip -r api-sign-token.zip sharedflowbundle/

# Import
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/sharedflows?action=import&name=api-sign-token" \
  -H "Content-Type: application/octet-stream" \
  -u "{admin-email}:{password}" \
  --data-binary @api-sign-token.zip

# Deploy
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/sharedflows/api-sign-token/revisions/1/deployments" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "{admin-email}:{password}" \
  -d "override=true"
```

### Package & Deploy `api-sign-request`

```bash
cd apigee-api-sign-validate/api-sign-request
zip -r api-sign-request.zip sharedflowbundle/

# Import
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/sharedflows?action=import&name=api-sign-request" \
  -H "Content-Type: application/octet-stream" \
  -u "{admin-email}:{password}" \
  --data-binary @api-sign-request.zip

# Deploy
curl -X POST \
  "https://{management-host}/v1/organizations/{org}/environments/{env}/sharedflows/api-sign-request/revisions/1/deployments" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "{admin-email}:{password}" \
  -d "override=true"
```

---

## 5. Proxy Integration Guide

### Outbound Signing (PreFlow Request)

```xml
<!-- 1. Set algorithm and keyId for this proxy -->
<AssignMessage name="AM-SetSignInputs">
  <AssignVariable><Name>sign.in.algorithm</Name><Value>RS256</Value></AssignVariable>
  <AssignVariable><Name>sign.in.keyId</Name><Value>my-key-2024</Value></AssignVariable>
</AssignMessage>

<!-- 2. Fetch token (cache hit on subsequent requests) -->
<FlowCallout name="FC-FetchToken" continueOnError="false">
  <SharedFlowBundle>api-sign-token</SharedFlowBundle>
</FlowCallout>

<!-- 3. Sign the request -->
<FlowCallout name="FC-SignRequest" continueOnError="false">
  <SharedFlowBundle>api-sign-request</SharedFlowBundle>
  <HookName>hook-sign-request</HookName>
</FlowCallout>
```

### Inbound Validation (PreFlow Request)

```xml
<AssignMessage name="AM-SetValidateInputs">
  <AssignVariable><Name>sign.in.algorithm</Name><Value>RS256</Value></AssignVariable>
  <AssignVariable><Name>sign.in.keyId</Name><Value>my-key-2024</Value></AssignVariable>
</AssignMessage>

<FlowCallout name="FC-FetchToken" continueOnError="false">
  <SharedFlowBundle>api-sign-token</SharedFlowBundle>
</FlowCallout>

<FlowCallout name="FC-ValidateRequest" continueOnError="false">
  <SharedFlowBundle>api-sign-request</SharedFlowBundle>
  <HookName>hook-validate-request</HookName>
</FlowCallout>
```

### Typical Usage Patterns

```
Outbound signing:
  AM-SetInputs → FC(api-sign-token) → FC(api-sign-request / hook-sign-request)

Inbound validation:
  AM-SetInputs → FC(api-sign-token) → FC(api-sign-request / hook-validate-request)

Subsequent requests (token cached):
  AM-SetInputs → FC(api-sign-token)        ← cache hit, ServiceCallout skipped
               → FC(api-sign-request / hook-sign-request)
```

---

## 6. JOSE Headers Configuration Guide

The `jose.headers` KVM entry is a JSON array of header descriptors. Each descriptor defines one JOSE header field.

### Descriptor Format

```json
{
  "name": "<header-name>",
  "value": "<value-or-reference>",
  "critical": true|false,
  "source": "flow|kvm|static"
}
```

### Source Types

| Source | Value Format | Resolution |
|--------|-------------|------------|
| `flow` | `flow:<variable-name>` | Reads from Apigee flow variable (e.g., `flow:sign.in.algorithm`) |
| `kvm` | `kvm:<kvm-key>` | Reads from `kvm.sign.<key>` flow variable |
| `static` | literal value | Uses the value as-is |

### Critical Flag

- When `critical: true`, the header name is added to the JWT `crit` array
- If a critical header resolves to null or empty, the JS policy throws `CRITICAL_HEADER_MISSING`
- The `crit` array is only included in the JWT header when at least one critical header exists

### Examples

**Standard RS256 with key rotation:**
```json
[
  {"name": "alg", "value": "flow:sign.in.algorithm", "critical": true,  "source": "flow"},
  {"name": "kid", "value": "flow:sign.in.keyId",     "critical": true,  "source": "flow"},
  {"name": "typ", "value": "JWT",                    "critical": false, "source": "static"}
]
```

**With custom headers:**
```json
[
  {"name": "alg", "value": "flow:sign.in.algorithm",   "critical": true,  "source": "flow"},
  {"name": "kid", "value": "flow:sign.in.keyId",       "critical": true,  "source": "flow"},
  {"name": "typ", "value": "JWT",                      "critical": false, "source": "static"},
  {"name": "x5t", "value": "flow:sign.in.thumbprint",  "critical": false, "source": "flow"},
  {"name": "iss", "value": "kvm:jose.issuer",          "critical": false, "source": "kvm"}
]
```

---

## 7. Request Components Configuration Guide

The `request.components` KVM entry is a JSON array that defines which parts of the HTTP request are included in the canonical digest.

### Component Format

```json
{
  "name": "<label>",
  "source": "flow|header|body",
  "value": "<source-reference>"
}
```

### Source Types

| Source | Value | Resolution |
|--------|-------|------------|
| `flow` | Apigee flow variable name | `context.getVariable(value)` |
| `header` | HTTP header name | `context.getVariable("request.header." + value)` |
| `body` | _(ignored)_ | `context.getVariable("request.content")` |

### Canonical String Assembly

1. Non-body components are formatted as `name: value` pairs
2. Pairs are joined with newline (`\n`)
3. The body (if present) is appended last without a label prefix

### Example: REST API (method + path + body)

```json
[
  {"name": "method", "source": "flow",   "value": "request.verb"},
  {"name": "path",   "source": "flow",   "value": "request.uri"},
  {"name": "body",   "source": "body",   "value": ""}
]
```

Produces:
```
method: POST
path: /api/v1/payments
{"amount":100,"currency":"USD"}
```

### Example: RPC-style API (method + path + specific headers)

```json
[
  {"name": "method",       "source": "flow",   "value": "request.verb"},
  {"name": "path",         "source": "flow",   "value": "request.uri"},
  {"name": "content-type", "source": "header", "value": "Content-Type"},
  {"name": "x-request-id", "source": "header", "value": "X-Request-Id"},
  {"name": "body",         "source": "body",   "value": ""}
]
```

Produces:
```
method: POST
path: /rpc/execute
content-type: application/json
x-request-id: abc-123-def
{"method":"doSomething","params":[1,2,3]}
```

### Example: Headers only (no body)

```json
[
  {"name": "method",       "source": "flow",   "value": "request.verb"},
  {"name": "path",         "source": "flow",   "value": "request.uri"},
  {"name": "content-type", "source": "header", "value": "Content-Type"}
]
```

---

## 8. Flow Variable Reference

| Variable | Direction | Shared Flow | Description |
|----------|-----------|-------------|-------------|
| `sign.in.algorithm` | Input (proxy) | sign-request | Signing algorithm (e.g., `RS256`, `PS256`, `ES256`) |
| `sign.in.keyId` | Input (proxy) | sign-request | Key ID for the Signature Service |
| `sign.accessToken` | Output | sign-token | Bearer token (from cache or fresh) |
| `sign.digest` | Internal | sign-request | SHA-256 Base64URL digest of canonical request |
| `sign.signingString` | Internal | sign-request | Unsigned JWT compact serialization |
| `sign.signature` | Output | sign-request | Signature returned by Signature Service |
| `sign.incomingSignature` | Internal | sign-request | Signature extracted from incoming request header |
| `sign.validationResult` | Internal | sign-request | `"true"` or `"false"` from Signature Service |
| `sign.error` | Output | both | Error code on failure |
| `sign.errorMessage` | Output | both | Human-readable error detail |
| `kvm.sign.token.loaded` | Internal | sign-token | Guard flag — prevents duplicate KVM reads in token flow |
| `kvm.sign.request.loaded` | Internal | sign-request | Guard flag — prevents duplicate KVM reads in request flow |

---

## 9. Error Reference

| Code | Shared Flow | HTTP Status | Trigger |
|------|-------------|-------------|---------|
| `TOKEN_FETCH_FAILED` | api-sign-token | 502 | `sign.accessToken` empty after ServiceCallout + ExtractVariables |
| `CRITICAL_HEADER_MISSING` | api-sign-request | _(JS error)_ | Critical JOSE header resolves to null or empty |
| `SIGN_REQUEST_FAILED` | api-sign-request | 502 | `sign.signature` empty after ServiceCallout + ExtractVariables |
| `SIGNATURE_HEADER_MISSING` | api-sign-request | _(JS error)_ | Signature header absent from incoming request |
| `SIGNATURE_VALIDATION_FAILED` | api-sign-request | 401 | `sign.validationResult != "true"` |

---

## Project Structure

```
apigee-api-sign-validate/
│
├── api-sign-token/                          ← Shared Flow 1: Token
│   └── sharedflowbundle/
│       ├── sharedflow.xml
│       └── policies/
│           ├── KVM-ReadSignConfig.xml
│           ├── AM-SetKvmLoaded.xml
│           ├── LookupCache-AccessToken.xml
│           ├── AM-BuildTokenRequest.xml
│           ├── SC-FetchToken.xml
│           ├── EV-ExtractToken.xml
│           ├── PopulateCache-AccessToken.xml
│           ├── AM-SetAccessToken.xml
│           └── RF-TokenFetchFailed.xml
│
├── api-sign-request/                        ← Shared Flow 2: Sign/Validate
│   └── sharedflowbundle/
│       ├── sharedflow.xml
│       ├── policies/
│       │   ├── KVM-ReadSignConfig.xml
│       │   ├── AM-SetKvmLoaded.xml
│       │   ├── JS-BuildSigningString.xml
│       │   ├── AM-BuildSignRequest.xml
│       │   ├── SC-CallSignService.xml
│       │   ├── EV-ExtractSignature.xml
│       │   ├── AM-AttachSignature.xml
│       │   ├── RF-SignFailed.xml
│       │   ├── JS-ExtractIncomingSignature.xml
│       │   ├── JS-BuildValidateDigest.xml
│       │   ├── AM-BuildValidateRequest.xml
│       │   ├── SC-CallValidateService.xml
│       │   ├── EV-ExtractValidationResult.xml
│       │   └── RF-ValidationFailed.xml
│       └── resources/
│           └── jsc/
│               ├── buildSigningString.js
│               ├── extractIncomingSignature.js
│               ├── buildValidateDigest.js
│               └── lib/
│                   └── canonicalize.js
│
└── README.md
```
