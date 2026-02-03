// JS-DebugTLSVariables.js
var tlsVars = [
    "tls.client.raw.cert",
    "tls.client.s.dn",
    "tls.client.i.dn", 
    "tls.client.cert.serial",
    "tls.client.cert.fingerprint",
    "tls.client.start.timestamp",
    "tls.cipher",
    "tls.protocol",
    "tls.server.name",
    "client.cn",
    "client.country",
    "client.email.address",
    "client.locality",
    "client.organization",
    "client.organization.unit",
    "client.state",
    "client.ssl.enabled",
    "client.scheme"
];

var result = {};
for (var i = 0; i < tlsVars.length; i++) {
    var varName = tlsVars[i];
    var value = context.getVariable(varName);
    result[varName] = value || "(empty)";
}

// Also check common headers where LBs put certs
var certHeaders = [
    "X-Client-Cert",
    "X-SSL-Client-Cert", 
    "X-SSL-Client-S-DN",
    "X-Forwarded-Client-Cert",
    "SSL_CLIENT_CERT",
    "X-ARR-ClientCert"
];

result["--- HEADERS ---"] = "";
for (var j = 0; j < certHeaders.length; j++) {
    var headerName = certHeaders[j];
    var headerValue = context.getVariable("request.header." + headerName);
    result["header." + headerName] = headerValue ? headerValue.substring(0, 100) + "..." : "(empty)";
}

context.setVariable("debug.tls.info", JSON.stringify(result, null, 2));
