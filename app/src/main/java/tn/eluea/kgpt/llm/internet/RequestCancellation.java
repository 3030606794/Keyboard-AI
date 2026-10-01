package tn.eluea.kgpt.llm.internet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Tracks resources while connecting, before a subscription exists. */
public final class RequestCancellation {
    private static final ThreadLocal<RequestCancellation> CURRENT = new ThreadLocal<>();
    private final List<Runnable> resources = new ArrayList<>();
    private boolean cancelled;
    public static void bind(RequestCancellation request) { CURRENT.set(request); }
    public static void unbind() { CURRENT.remove(); }
    public static RequestCancellation current() { return CURRENT.get(); }
    public synchronized boolean isCancelled() { return cancelled; }
    public synchronized void register(Runnable close) throws IOException {
        if (cancelled) {
            close.run();
            throw new IOException("Request cancelled");
        }
        resources.add(close);
    }
    public synchronized void unregister(Runnable close) { resources.remove(close); }
    public void cancel() {
        List<Runnable> closing;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            closing = new ArrayList<>(resources);
            resources.clear();
        }
        for (Runnable close : closing) {
            try { close.run(); } catch (RuntimeException ignored) { }
        }
    }
}
