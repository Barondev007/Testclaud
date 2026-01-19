package com.vordel.mime;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Stub class for Axway HeaderSet.
 * This is only used for compilation - the actual class is provided by Axway runtime.
 */
public class HeaderSet {

    public Collection<String> getHeaderSet() {
        return new ArrayList<>();
    }

    public ArrayList<String> getHeaderValues(String name) {
        return new ArrayList<>();
    }

    public String getHeader(String name) {
        return null;
    }

    public void remove(String name) {
        // Stub
    }

    public void setHeader(String name, String value) {
        // Stub
    }

    public int size() {
        return 0;
    }
}
