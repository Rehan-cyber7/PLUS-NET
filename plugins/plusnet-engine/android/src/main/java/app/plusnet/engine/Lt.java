package app.plusnet.engine;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Calls into libtorrent4j by name instead of linking against it. The torrent library is the one part
 * of the app that could not be compiled against while it was written, so it is reached this way: if
 * the library's API differs from what is expected, only torrents report an error (naming the exact
 * method), and the rest of the app builds and works as usual.
 */
final class Lt {
    private Lt() { }

    static boolean present() {
        try {
            Class.forName("org.libtorrent4j.SessionManager");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Class<?> find(String name) throws IOException {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IOException("The torrent engine is not part of this build (" + name + " is missing)");
        }
    }

    static Object call(Object target, String name, Object... args) throws IOException {
        return invoke(target.getClass(), target, name, args);
    }

    static Object callStatic(Class<?> c, String name, Object... args) throws IOException {
        return invoke(c, null, name, args);
    }

    static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }

    static boolean bool(Object o) { return o instanceof Boolean && (Boolean) o; }

    private static Object invoke(Class<?> c, Object target, String name, Object[] args) throws IOException {
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Object[] converted = convert(m.getParameterTypes(), args);
            if (converted == null) continue;
            try {
                return m.invoke(target, converted);
            } catch (InvocationTargetException e) {
                Throwable t = e.getCause() != null ? e.getCause() : e;
                throw new IOException(c.getSimpleName() + "." + name + ": " + t);
            } catch (IllegalAccessException e) {
                throw new IOException(c.getSimpleName() + "." + name + " is not accessible");
            }
        }
        throw new IOException("The torrent library has no " + c.getName() + "." + name + "(" + args.length + " arguments)");
    }

    /** The arguments in the types the method wants, or null if they don't fit. */
    private static Object[] convert(Class<?>[] want, Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Class<?> w = want[i];
            Object a = args[i];
            if (a == null) {
                if (w.isPrimitive()) return null;
                out[i] = null;
            } else if (w.isInstance(a)) {
                out[i] = a;
            } else if (a instanceof Number && w.isPrimitive() && w != boolean.class && w != char.class) {
                Number n = (Number) a;
                out[i] = w == int.class ? (Object) n.intValue() : w == long.class ? (Object) n.longValue()
                    : w == double.class ? (Object) n.doubleValue() : w == float.class ? (Object) n.floatValue()
                    : w == short.class ? (Object) n.shortValue() : (Object) n.byteValue();
            } else if (a instanceof Boolean && w == boolean.class) {
                out[i] = a;
            } else {
                return null;
            }
        }
        return out;
    }
}
