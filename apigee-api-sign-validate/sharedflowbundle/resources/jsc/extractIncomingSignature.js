/**
 * JS-ExtractIncomingSignature
 *
 * Extracts the signature from a dynamically-named request header.
 * The header name is read from kvm.sign.signature.header.name.
 * Sets sign.incomingSignature on success, or raises an error if the header is missing.
 */

var headerName = context.getVariable("kvm.sign.signature.header.name");
var sig = context.getVariable("request.header." + headerName);

if (!sig || sig.trim() === "") {
  context.setVariable("sign.error", "SIGNATURE_HEADER_MISSING");
  context.setVariable("sign.errorMessage", "Required header '" + headerName + "' is absent");
  throw new Error("SIGNATURE_HEADER_MISSING");
}

context.setVariable("sign.incomingSignature", sig.trim());
