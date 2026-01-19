package com.vordel.mime;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;

/**
 * Stub class for Axway QueryStringHeaderSet.
 * This is only used for compilation and testing.
 * The actual class is provided by Axway runtime.
 */
public class QueryStringHeaderSet extends HeaderSet {

    public QueryStringHeaderSet() {
        super();
    }

    public QueryStringHeaderSet(String queryString) {
        super();
        if (queryString != null && !queryString.isEmpty()) {
            parseQueryString(queryString);
        }
    }

    private void parseQueryString(String queryString) {
        // Remove leading '?' if present
        if (queryString.startsWith("?")) {
            queryString = queryString.substring(1);
        }

        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            String key = decode(keyValue[0]);
            String value = keyValue.length > 1 ? decode(keyValue[1]) : "";

            ArrayList<String> values = headers.get(key);
            if (values == null) {
                values = new ArrayList<>();
                headers.put(key, values);
            }
            values.add(value);
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
