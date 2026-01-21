package com.vordel.mime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Stub class for Axway HeaderSet.
 * This is only used for compilation and testing.
 * The actual class is provided by Axway runtime.
 */
public class HeaderSet implements Iterable<String> {

    protected Map<String, ArrayList<String>> headers = new HashMap<>();

    /**
     * Returns an iterator over header names.
     */
    @Override
    public Iterator<String> iterator() {
        return headers.keySet().iterator();
    }

    /**
     * Returns the collection of header names.
     */
    public Collection<String> getHeaderNames() {
        return headers.keySet();
    }

    /**
     * Returns values for a specific header.
     */
    public ArrayList<String> getHeaderValues(String name) {
        // Case-insensitive lookup
        for (Map.Entry<String, ArrayList<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Returns first value for a specific header.
     */
    public String getHeader(String name) {
        ArrayList<String> values = getHeaderValues(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    public void remove(String name) {
        String keyToRemove = null;
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                keyToRemove = key;
                break;
            }
        }
        if (keyToRemove != null) {
            headers.remove(keyToRemove);
        }
    }

    public void setHeader(String name, String value) {
        ArrayList<String> values = new ArrayList<>();
        values.add(value);
        headers.put(name, values);
    }

    public void addHeader(String name, String value) {
        ArrayList<String> values = headers.get(name);
        if (values == null) {
            values = new ArrayList<>();
            headers.put(name, values);
        }
        values.add(value);
    }

    public int size() {
        return headers.size();
    }
}
