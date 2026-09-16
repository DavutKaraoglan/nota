package com.nota.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Artist {
    public long id;
    public String name;
    public final List<Track> tracks = new ArrayList<Track>();
    public final Map<Long, Album> albums = new LinkedHashMap<Long, Album>();
}
