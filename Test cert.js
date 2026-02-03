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

var output = "=== TLS VARIABLES ===\n";
for (var i = 0; i < tlsVars.length; i++) {
    var varName = tlsVars[i];
    var value = context.getVariable(varName);
    output += varName + " = " + (value ? value : "(empty)") + "\n";
}

// Check common headers where LBs put certs
var certHeaders = [
    "X-Client-Cert",
    "X-SSL-Client-Cert", 
    "X-SSL-Client-S-DN",
    "X-Forwarded-Client-Cert",
    "SSL_CLIENT_CERT",
    "X-ARR-ClientCert",
    "X-Client-Certificate",
    "X-SSL-CERT"
];

output += "\n=== CERTIFICATE HEADERS ===\n";
for (var j = 0; j < certHeaders.length; j++) {
    var headerName = certHeaders[j];
    var headerValue = context.getVariable("request.header." + headerName);
    if (headerValue) {
        // Truncate long values
        var displayValue = headerValue.length > 100 ? headerValue.substring(0, 100) + "..." : headerValue;
        output += headerName + " = " + displayValue + "\n";
    } else {
        output += headerName + " = (empty)\n";
    }
}

// List all request headers to find any cert-related ones
output += "\n=== ALL REQUEST HEADERS ===\n";
var headerNames = context.getVariable("request.headers.names");
if (headerNames) {
    var headers = String(headerNames).replace(/[\[\]]/g, "").split(", ");
    for (var k = 0; k < headers.length; k++) {
        var hName = headers[k].trim();
        if (hName) {
            var hValue = context.getVariable("request.header." + hName);
            output += hName + " = " + (hValue ? hValue.substring(0, 80) : "(empty)") + "\n";
        }
    }
}

context.setVariable("debug.tls.info", output);
