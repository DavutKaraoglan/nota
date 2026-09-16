package com.nota.model;

import java.util.ArrayList;
import java.util.List;

public class Album {
    public long id;
    public String title;
    public String artist;
    public int year;
    public final List<Track> tracks = new ArrayList<Track>();

    public long durationMs() {
        long total = 0;
        for (Track t : tracks) total += t.durationMs;
        return total;
    }
}
