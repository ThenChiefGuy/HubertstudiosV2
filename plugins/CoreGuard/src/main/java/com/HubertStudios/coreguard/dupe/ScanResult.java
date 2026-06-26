package com.HubertStudios.coreguard.dupe;

import java.util.ArrayList;
import java.util.List;

public class ScanResult {
    private final List<String> problems = new ArrayList<>();

    public void add(String problem) { problems.add(problem); }
    public boolean clean() { return problems.isEmpty(); }
    public int count() { return problems.size(); }
    public List<String> problems() { return List.copyOf(problems); }
}
