# KVM Cache Refresh API for Apigee Edge

This API proxy allows you to refresh KVM cache entries via a REST API call, perfect for CI/CD pipelines.

## The Problem

In Apigee Edge, KVM values are cached at the Message Processor level. When you update a KVM entry via the Management API or UI, the cached values on Message Processors may not be invalidated immediately, leading to stale data being served.

## The Solution

This proxy provides an endpoint that:
1. Fetches the current KVM entry via the Management API
2. Re-PUTs the same value back (which invalidates the MP cache)
3. Returns a success/failure response

## Setup Instructions

### 1. Create the Configuration KVM

Create a KVM named `kvm-refresh-config` in your environment to store Management API credentials:

```bash
# Create the KVM
curl -X POST \
  -H "Content-Type: application/json" \
  -u "$APIGEE_USER:$APIGEE_PASS" \
  -d '{"name": "kvm-refresh-config", "encrypted": true}' \
  "https://api.enterprise.apigee.com/v1/organizations/$ORG/environments/$ENV/keyvaluemaps"

# Add the authorization header (Base64 encoded credentials)
AUTH_HEADER="Basic $(echo -n "$APIGEE_USER:$APIGEE_PASS" | base64)"

curl -X POST \
  -H "Content-Type: application/json" \
  -u "$APIGEE_USER:$APIGEE_PASS" \
  -d "{\"name\": \"mgmt.authorization\", \"value\": \"$AUTH_HEADER\"}" \
  "https://api.enterprise.apigee.com/v1/organizations/$ORG/environments/$ENV/keyvaluemaps/kvm-refresh-config/entries"
```

### 2. Create an API Product and Developer App

1. Create an API Product that includes this proxy
2. Create a Developer
3. Create a Developer App to get an API key

### 3. Deploy the Proxy

```bash
# Zip the proxy bundle
cd kvm-refresh-proxy
zip -r kvm-refresh.zip apiproxy

# Import the proxy
curl -X POST \
  -H "Content-Type: multipart/form-data" \
  -u "$APIGEE_USER:$APIGEE_PASS" \
  -F "file=@kvm-refresh.zip" \
  "https://api.enterprise.apigee.com/v1/organizations/$ORG/apis?action=import&name=kvm-refresh"

# Deploy to environment
curl -X POST \
  -u "$APIGEE_USER:$APIGEE_PASS" \
  "https://api.enterprise.apigee.com/v1/organizations/$ORG/environments/$ENV/apis/kvm-refresh/revisions/1/deployments"
```

## API Usage

### Refresh a Specific KVM Entry

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "x-api-key: YOUR_API_KEY" \
  -d '{
    "kvmName": "my-config-kvm",
    "keyName": "database.url"
  }' \
  "https://your-org-your-env.apigee.net/kvm-refresh/refresh"
```

### Response

```json
{
  "success": true,
  "kvmName": "my-config-kvm",
  "keyName": "database.url",
  "message": "KVM cache refreshed successfully. Entry 'database.url' in KVM 'my-config-kvm' has been invalidated and reloaded.",
  "timestamp": "2024-03-12T10:30:00.000Z"
}
```

### Query Parameters (Alternative)

You can also pass parameters as query strings:

```bash
curl -X POST \
  -H "x-api-key: YOUR_API_KEY" \
  "https://your-org-your-env.apigee.net/kvm-refresh/refresh?kvmName=my-config-kvm&keyName=database.url"
```

### Health Check

```bash
curl "https://your-org-your-env.apigee.net/kvm-refresh/health"
```

## CI/CD Integration

### GitLab CI Example

```yaml
refresh-kvm:
  stage: post-deploy
  script:
    - |
      curl -X POST \
        -H "Content-Type: application/json" \
        -H "x-api-key: $APIGEE_KVM_REFRESH_KEY" \
        -d '{"kvmName": "'"$KVM_NAME"'", "keyName": "'"$KEY_NAME"'"}' \
        "https://$APIGEE_HOST/kvm-refresh/refresh"
  only:
    - main
```

### Jenkins Example

```groovy
stage('Refresh KVM Cache') {
    steps {
        sh '''
            curl -X POST \
              -H "Content-Type: application/json" \
              -H "x-api-key: ${APIGEE_KVM_REFRESH_KEY}" \
              -d '{"kvmName": "my-config-kvm", "keyName": "updated-key"}' \
              "https://${APIGEE_HOST}/kvm-refresh/refresh"
        '''
    }
}
```

## Security Considerations

1. **API Key Protection**: This API is protected by an API key. Keep the key secure.
2. **Management API Credentials**: The proxy stores Management API credentials in an encrypted KVM.
3. **Network Access**: Consider restricting access to this API to your CI/CD IP ranges using Apigee's access control policies.

## Optional: Add IP Restriction

Add this policy to restrict access to specific IPs (e.g., your CI/CD runners):

```xml
<AccessControl async="false" continueOnError="false" enabled="true" name="AC-RestrictIP">
    <IPRules noRuleMatchAction="DENY">
        <MatchRule action="ALLOW">
            <SourceAddress mask="32">10.0.0.1</SourceAddress>
            <SourceAddress mask="24">192.168.1.0</SourceAddress>
        </MatchRule>
    </IPRules>
</AccessControl>
```
