package com.axway.apim.openapi.validator;

import com.axway.apim.openapi.validator.Utils.TraceLevel;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Schema provider that fetches API specifications from Axway API Manager.
 */
public class APIManagerSchemaProvider {

    private final String apiManagerUrl;
    private final String username;
    private final String password;
    private boolean useOriginalAPISpec = false;

    public APIManagerSchemaProvider(String apiManagerUrl, String username, String password) {
        this.apiManagerUrl = apiManagerUrl.endsWith("/") ? apiManagerUrl.substring(0, apiManagerUrl.length() - 1) : apiManagerUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Get the API specification for the given API ID.
     *
     * @param apiId The API ID
     * @return The API specification as a string (YAML or JSON)
     * @throws Exception If the API specification cannot be retrieved
     */
    public String getSchema(String apiId) throws Exception {
        String endpoint;
        if (useOriginalAPISpec) {
            endpoint = apiManagerUrl + "/api/portal/v1.3/apirepo/" + apiId + "/download?original=true";
        } else {
            endpoint = apiManagerUrl + "/api/portal/v1.3/apirepo/" + apiId + "/download";
        }

        Utils.traceMessage("Fetching API spec from: " + endpoint, TraceLevel.INFO);

        try {
            // Disable SSL verification for self-signed certs (common in dev environments)
            disableSSLVerification();

            URL url = new URL(endpoint);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json, application/yaml, text/yaml");

            // Add basic auth
            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);

            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("Failed to fetch API spec. HTTP " + responseCode + " from " + endpoint);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            }

            String spec = response.toString();
            Utils.traceMessage("Successfully fetched API spec, length: " + spec.length() + " chars", TraceLevel.INFO);
            return spec;

        } catch (Exception e) {
            Utils.traceMessage("Error fetching API spec for ID: " + apiId, e, TraceLevel.ERROR);
            throw e;
        }
    }

    private void disableSSLVerification() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Utils.traceMessage("Could not disable SSL verification", e, TraceLevel.WARN);
        }
    }

    public boolean isUseOriginalAPISpec() {
        return useOriginalAPISpec;
    }

    public void setUseOriginalAPISpec(boolean useOriginalAPISpec) {
        this.useOriginalAPISpec = useOriginalAPISpec;
    }
}
