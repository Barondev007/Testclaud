package com.apigee.flow.message;

import java.util.HashMap;
import java.util.Map;

/**
 * Test stub for Apigee MessageContext.
 */
public class MessageContext {

    private final Map<String, Object> variables = new HashMap<>();

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getVariable(String name) {
        Object value = variables.get(name);
        return (T) value;
    }

    public Map<String, Object> getVariables() {
        return new HashMap<>(variables);
    }
}
