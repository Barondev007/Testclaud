/**
 * Shared canonicalization logic for API request signing and validation.
 * Included via <IncludeURL> in both JS-BuildSigningString and JS-BuildValidateDigest policies.
 *
 * Provides: buildCanonicalString(components)
 *   - components: parsed JSON array from kvm.sign.request.components
 *   - Returns the canonical newline-delimited string
 */

function buildCanonicalString(components) {
  var parts = [];
  var bodyValue = null;

  for (var i = 0; i < components.length; i++) {
    var comp = components[i];
    var resolved = null;

    if (comp.source === "flow") {
      resolved = context.getVariable(comp.value);
    } else if (comp.source === "header") {
      resolved = context.getVariable("request.header." + comp.value);
    } else if (comp.source === "body") {
      resolved = context.getVariable("request.content");
    }

    if (resolved === null || typeof resolved === "undefined") {
      resolved = "";
    }

    if (comp.source === "body") {
      bodyValue = resolved;
    } else {
      parts.push(comp.name + ": " + resolved);
    }
  }

  var canonical = parts.join("\n");

  if (bodyValue !== null) {
    if (canonical.length > 0) {
      canonical = canonical + "\n" + bodyValue;
    } else {
      canonical = bodyValue;
    }
  }

  return canonical;
}

/**
 * Compute SHA-256 digest of the canonical string and return Base64URL encoding.
 * Uses the Apigee JS crypto object.
 */
function computeDigest(canonicalString) {
  var sha256 = crypto.getSHA256();
  sha256.update(canonicalString);
  var hexDigest = sha256.digest("hex");
  return hexToBase64Url(hexDigest);
}

/**
 * Convert a hex string to Base64URL encoding.
 */
function hexToBase64Url(hex) {
  var bytes = [];
  for (var i = 0; i < hex.length; i += 2) {
    bytes.push(parseInt(hex.substr(i, 2), 16));
  }
  var b64 = btoa(String.fromCharCode.apply(null, bytes));
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}
