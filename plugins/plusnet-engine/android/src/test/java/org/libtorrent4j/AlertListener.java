package org.libtorrent4j; public interface AlertListener { int[] types(); void alert(org.libtorrent4j.alerts.Alert<?> alert); }
