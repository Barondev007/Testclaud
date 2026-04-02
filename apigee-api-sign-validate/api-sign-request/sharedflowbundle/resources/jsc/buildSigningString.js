/**
 * JS-BuildSigningString
 *
 * Builds the unsigned JWT compact serialization for the Signature Service.
 * Steps:
 *   1. Canonicalize request components
 *   2. Compute SHA-256 digest (Base64URL)
 *   3. Resolve JOSE headers
 *   4. Build JWT header + payload
 *   5. Produce unsigned compact serialization: base64url(header).base64url(payload)
 *
 * Depends on: lib/canonicalize.js (included via IncludeURL)
 */

// --- Base64URL encoding for JWT segments ---
function base64url(str) {
  var b64 = btoa(unescape(encodeURIComponent(str)));
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}

// --- Step 1: Parse request components and build canonical string ---
var componentsJson = context.getVariable("kvm.sign.request.components");
var components = JSON.parse(componentsJson);
var canonicalString = buildCanonicalString(components);

// --- Step 2: Compute SHA-256 digest ---
var digest = computeDigest(canonicalString);
context.setVariable("sign.digest", digest);

// --- Step 3: Resolve JOSE headers ---
var joseHeadersJson = context.getVariable("kvm.sign.jose.headers");
var joseDescriptors = JSON.parse(joseHeadersJson);
var joseHeader = {};
var critArray = [];

for (var i = 0; i < joseDescriptors.length; i++) {
  var desc = joseDescriptors[i];
  var resolvedValue = null;

  if (desc.source === "flow") {
    // Value format: "flow:<varname>"
    var varName = desc.value;
    if (varName.indexOf("flow:") === 0) {
      varName = varName.substring(5);
    }
    resolvedValue = context.getVariable(varName);
  } else if (desc.source === "kvm") {
    // Value format: "kvm:<key>"
    var kvmKey = desc.value;
    if (kvmKey.indexOf("kvm:") === 0) {
      kvmKey = kvmKey.substring(4);
    }
    resolvedValue = context.getVariable("kvm.sign." + kvmKey);
  } else if (desc.source === "static") {
    resolvedValue = desc.value;
  } else {
    // No recognized source — treat value as static literal
    resolvedValue = desc.value;
  }

  // Check critical headers
  if (desc.critical === true) {
    if (resolvedValue === null || resolvedValue === "" || typeof resolvedValue === "undefined") {
      context.setVariable("sign.error", "CRITICAL_HEADER_MISSING");
      context.setVariable("sign.errorMessage", "Critical JOSE header '" + desc.name + "' could not be resolved");
      throw new Error("CRITICAL_HEADER_MISSING: " + desc.name);
    }
    critArray.push(desc.name);
  }

  joseHeader[desc.name] = resolvedValue;
}

// --- Step 4: Add crit array if non-empty ---
if (critArray.length > 0) {
  joseHeader["crit"] = critArray;
}

// --- Step 5: Build JWT payload ---
var nowSecs = Math.floor(Date.now() / 1000);
var jti = Math.random().toString(36).substring(2) + Date.now().toString(36);

var jwtPayload = {
  digest: digest,
  iat: nowSecs,
  exp: nowSecs + 300,
  jti: jti
};

// --- Step 6: Produce unsigned compact serialization ---
var headerSegment = base64url(JSON.stringify(joseHeader));
var payloadSegment = base64url(JSON.stringify(jwtPayload));
var signingString = headerSegment + "." + payloadSegment;

context.setVariable("sign.signingString", signingString);
