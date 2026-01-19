package com.axway.apim.openapi.validator;

import com.vordel.mime.HeaderSet;
import com.vordel.mime.QueryStringHeaderSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class HeaderSetTest {

    @Test
    void testEmptyHeaderSet() {
        HeaderSet headers = new HeaderSet();
        assertEquals(0, headers.size());
        assertTrue(headers.getHeaderSet().isEmpty());
    }

    @Test
    void testSetAndGetHeader() {
        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

        assertEquals("application/json", headers.getHeader("Content-Type"));
        assertEquals(1, headers.size());
    }

    @Test
    void testCaseInsensitiveLookup() {
        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

        assertEquals("application/json", headers.getHeader("content-type"));
        assertEquals("application/json", headers.getHeader("CONTENT-TYPE"));
        assertEquals("application/json", headers.getHeader("Content-type"));
    }

    @Test
    void testGetHeaderValues() {
        HeaderSet headers = new HeaderSet();
        headers.setHeader("Accept", "application/json");

        ArrayList<String> values = headers.getHeaderValues("Accept");
        assertNotNull(values);
        assertEquals(1, values.size());
        assertEquals("application/json", values.get(0));
    }

    @Test
    void testAddMultipleValues() {
        HeaderSet headers = new HeaderSet();
        headers.addHeader("Accept", "application/json");
        headers.addHeader("Accept", "text/html");

        ArrayList<String> values = headers.getHeaderValues("Accept");
        assertNotNull(values);
        assertEquals(2, values.size());
        assertTrue(values.contains("application/json"));
        assertTrue(values.contains("text/html"));
    }

    @Test
    void testRemoveHeader() {
        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");
        headers.setHeader("Authorization", "Bearer token");

        assertEquals(2, headers.size());

        headers.remove("Content-Type");
        assertEquals(1, headers.size());
        assertNull(headers.getHeader("Content-Type"));
        assertEquals("Bearer token", headers.getHeader("Authorization"));
    }

    @Test
    void testRemoveHeaderCaseInsensitive() {
        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

        headers.remove("content-type");
        assertNull(headers.getHeader("Content-Type"));
    }

    @Test
    void testGetHeaderSet() {
        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");
        headers.setHeader("Authorization", "Bearer token");

        Collection<String> headerNames = headers.getHeaderSet();
        assertEquals(2, headerNames.size());
        assertTrue(headerNames.contains("Content-Type"));
        assertTrue(headerNames.contains("Authorization"));
    }

    @Test
    void testGetNonExistentHeader() {
        HeaderSet headers = new HeaderSet();
        assertNull(headers.getHeader("Non-Existent"));
        assertNull(headers.getHeaderValues("Non-Existent"));
    }

    // QueryStringHeaderSet tests

    @Test
    void testEmptyQueryString() {
        QueryStringHeaderSet params = new QueryStringHeaderSet();
        assertEquals(0, params.size());
    }

    @Test
    void testParseSimpleQueryString() {
        QueryStringHeaderSet params = new QueryStringHeaderSet("name=John");

        assertEquals("John", params.getHeader("name"));
        assertEquals(1, params.size());
    }

    @Test
    void testParseMultipleParams() {
        QueryStringHeaderSet params = new QueryStringHeaderSet("name=John&age=30&city=NYC");

        assertEquals("John", params.getHeader("name"));
        assertEquals("30", params.getHeader("age"));
        assertEquals("NYC", params.getHeader("city"));
        assertEquals(3, params.size());
    }

    @Test
    void testParseQueryStringWithLeadingQuestionMark() {
        QueryStringHeaderSet params = new QueryStringHeaderSet("?name=John&age=30");

        assertEquals("John", params.getHeader("name"));
        assertEquals("30", params.getHeader("age"));
    }

    @Test
    void testParseEncodedQueryString() {
        QueryStringHeaderSet params = new QueryStringHeaderSet("name=John%20Doe&city=New%20York");

        assertEquals("John Doe", params.getHeader("name"));
        assertEquals("New York", params.getHeader("city"));
    }

    @Test
    void testParseMultipleValuesForSameKey() {
        QueryStringHeaderSet params = new QueryStringHeaderSet("color=red&color=blue&color=green");

        ArrayList<String> values = params.getHeaderValues("color");
        assertNotNull(values);
        assertEquals(3, values.size());
        assertTrue(values.contains("red"));
        assertTrue(values.contains("blue"));
        assertTrue(values.contains("green"));
    }

    @Test
    void testParseEmptyValue() {
        QueryStringHeaderSet params = new QueryStringHeaderSet("name=&age=30");

        assertEquals("", params.getHeader("name"));
        assertEquals("30", params.getHeader("age"));
    }

    @Test
    void testParseNoValue() {
        QueryStringHeaderSet params = new QueryStringHeaderSet("flag&name=John");

        assertEquals("", params.getHeader("flag"));
        assertEquals("John", params.getHeader("name"));
    }

    @Test
    void testNullQueryString() {
        QueryStringHeaderSet params = new QueryStringHeaderSet(null);
        assertEquals(0, params.size());
    }

    @Test
    void testEmptyStringQueryString() {
        QueryStringHeaderSet params = new QueryStringHeaderSet("");
        assertEquals(0, params.size());
    }
}
