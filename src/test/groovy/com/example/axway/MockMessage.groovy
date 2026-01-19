package com.example.axway

/**
 * Mock Axway Message class for testing outside Axway environment.
 */
class MockMessage {
    private Map<String, Object> attributes = [:]

    def get(String key) {
        return attributes.get(key)
    }

    def put(String key, Object value) {
        attributes.put(key, value)
    }

    Map<String, Object> getAll() {
        return attributes
    }

    void clear() {
        attributes.clear()
    }

    @Override
    String toString() {
        return "MockMessage${attributes}"
    }
}
