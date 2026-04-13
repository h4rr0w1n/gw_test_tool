package com.amhs.swim.test.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Centralized manager for tracking and storing test execution results per session.
 * 
 * Implements a Singleton pattern to ensure a consistent state across different 
 * GUI components and test drivers. Results stored here are used for final 
 * report generation (e.g., Excel export).
 */
public class ResultManager {
    private static ResultManager instance;
    private final List<TestResult> results = Collections.synchronizedList(new ArrayList<>());

    private ResultManager() {}

    public static synchronized ResultManager getInstance() {
        if (instance == null) {
            instance = new ResultManager();
        }
        return instance;
    }

    public void addResult(TestResult result) {
        results.add(result);
    }

    public List<TestResult> getResults() {
        return new ArrayList<>(results);
    }

    public void clear() {
        results.clear();
    }
}
