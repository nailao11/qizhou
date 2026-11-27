package com.anqu.ssh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class FileOrderTest {
    @Test public void sortsFilesBySizeAndKeepsFoldersFirst() {
        List<RemoteFiles.Entry> items = new ArrayList<>(List.of(
            new RemoteFiles.Entry("short.txt", false, false, 1),
            new RemoteFiles.Entry("a.txt", false, false, 1024),
            new RemoteFiles.Entry("b-folder", true, false, 8096),
            new RemoteFiles.Entry("b.txt", false, false, 1024),
            new RemoteFiles.Entry("a-folder", true, false, 0)));
        items.sort(RemoteFiles.LARGEST_FIRST);
        List<String> names = items.stream().map(RemoteFiles.Entry::name).toList();
        assertEquals(List.of("a-folder", "b-folder", "a.txt", "b.txt", "short.txt"), names);
    }
}
