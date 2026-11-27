package org.libtorrent4j.alerts; import org.libtorrent4j.TorrentHandle;
public class AddTorrentAlert extends Alert<Object> { private final TorrentHandle h; public AddTorrentAlert(TorrentHandle h) { this.h = h; } public TorrentHandle handle() { return h; } }
