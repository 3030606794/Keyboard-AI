package tn.eluea.kgpt.llm.internet;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RequestCancellationTest {
    @Test public void closesConnectingAndStreamingResourcesOnlyOnce() throws Exception {
        RequestCancellation request = new RequestCancellation(); AtomicInteger closed = new AtomicInteger();
        request.register(closed::incrementAndGet); request.register(closed::incrementAndGet);
        request.cancel(); request.cancel(); assertEquals(2, closed.get());
    }
    @Test public void cancelsResourceRegisteredAfterStop() {
        RequestCancellation request = new RequestCancellation(); AtomicInteger closed = new AtomicInteger();
        request.cancel();
        try { request.register(closed::incrementAndGet); fail("Must reject late connections"); }
        catch (IOException expected) { assertEquals(1, closed.get()); }
    }
    @Test public void cancellationDoesNotCloseOtherRequestResources() throws Exception {
        RequestCancellation first = new RequestCancellation(), second = new RequestCancellation();
        AtomicInteger closed = new AtomicInteger(); second.register(closed::incrementAndGet);
        first.cancel(); assertFalse(second.isCancelled()); assertEquals(0, closed.get());
    }
}
