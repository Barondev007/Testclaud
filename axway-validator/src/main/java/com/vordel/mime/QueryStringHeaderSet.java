package com.vordel.mime;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Stub class for Axway QueryStringHeaderSet.
 * This is only used for compilation - the actual class is provided by Axway runtime.
 */
public class QueryStringHeaderSet extends HeaderSet {

    public QueryStringHeaderSet() {
        super();
    }

    public QueryStringHeaderSet(String queryString) {
        super();
    }

    @Override
    public Collection<String> getHeaderSet() {
        return new ArrayList<>();
    }

    @Override
    public ArrayList<String> getHeaderValues(String name) {
        return new ArrayList<>();
    }
}
