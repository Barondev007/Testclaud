package com.vordel.mime;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Test stub for Axway QueryStringHeaderSet.
 * Mimics the real QueryStringHeaderSet behavior for unit testing.
 */
public class QueryStringHeaderSet implements Iterable<String> {

    private Map<String, ArrayList<String>> params = new HashMap<>();

    public QueryStringHeaderSet() {
    }

    public QueryStringHeaderSet(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return;
        }

        // Remove leading ? if present
        if (queryString.startsWith("?")) {
            queryString = queryString.substring(1);
        }

        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key;
            String value;

            if (idx > 0) {
                key = pair.substring(0, idx);
                value = pair.substring(idx + 1);
            } else if (idx == 0) {
                continue; // Skip if no key
            } else {
                key = pair;
                value = "";
            }

            // URL decode the value
            try {
                value = URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // Keep original value
            }

            addHeader(key, value);
        }
    }

    @Override
    public Iterator<String> iterator() {
        return params.keySet().iterator();
    }

    public ArrayList<String> getHeaderValues(String name) {
        for (Map.Entry<String, ArrayList<String>> entry : params.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String getHeader(String name) {
        ArrayList<String> values = getHeaderValues(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    public void setHeader(String name, String value) {
        ArrayList<String> values = new ArrayList<>();
        values.add(value);
        params.put(name, values);
    }

    public void addHeader(String name, String value) {
        ArrayList<String> values = params.get(name);
        if (values == null) {
            values = new ArrayList<>();
            params.put(name, values);
        }
        values.add(value);
    }

    public int size() {
        return params.size();
    }
}
