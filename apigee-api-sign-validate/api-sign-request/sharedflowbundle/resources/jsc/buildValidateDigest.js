/**
 * JS-BuildValidateDigest
 *
 * Builds the canonical request string and computes its SHA-256 digest
 * for signature validation. Uses shared canonicalization logic from
 * lib/canonicalize.js (included via IncludeURL).
 *
 * Only produces sign.digest — does NOT build a JWT signing string.
 */

var componentsJson = context.getVariable("kvm.sign.request.components");
var components = JSON.parse(componentsJson);
var canonicalString = buildCanonicalString(components);
var digest = computeDigest(canonicalString);

context.setVariable("sign.digest", digest);
