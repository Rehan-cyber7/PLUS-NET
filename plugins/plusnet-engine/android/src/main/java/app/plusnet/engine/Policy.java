package app.plusnet.engine;

import java.util.function.Predicate;

/** Which networks may carry a download. */
final class Policy {
    /** The order "Wi-Fi first" prefers: a cable, then Wi-Fi, mobile data last. */
    static final String[] PRIORITY = {"ethernet", "wifi", "cellular"};

    /**
     * Together: every usable network works at once. Backup: only the best usable one does, and the
     * others take over when it is gone and give way again when it is back.
     */
    static boolean allowed(boolean together, String id, Predicate<String> usable) {
        if (together) return true;
        for (String p : PRIORITY) if (usable.test(p)) return p.equals(id);
        return false;
    }
}
