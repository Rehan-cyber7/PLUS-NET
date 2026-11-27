package org.libtorrent4j; public class TorrentInfo { public static TorrentInfo bdecode(byte[] b) { return new TorrentInfo(); }
 public String name() { return "Test Torrent"; } public long totalSize() { return 12345L; } public int numFiles() { return 2; } }
