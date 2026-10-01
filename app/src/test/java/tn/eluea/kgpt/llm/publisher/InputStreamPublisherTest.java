package tn.eluea.kgpt.llm.publisher;

import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class InputStreamPublisherTest {
    @Test public void utf8AndRepeatedDemandDeliverOnlyOnce() throws Exception {
        StringBuilder text = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];
        new InputStreamPublisher(new ByteArrayInputStream("你好\n世界\n".getBytes(StandardCharsets.UTF_8)), s -> s)
                .subscribe(new Subscriber<String>() {
                    public void onSubscribe(Subscription s) { s.request(1); s.request(1); }
                    public void onNext(String s) { text.append(s); }
                    public void onError(Throwable t) { error[0] = t; done.countDown(); }
                    public void onComplete() { done.countDown(); }
                });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNull(error[0]);
        assertEquals("你好世界", text.toString());
    }
    @Test public void parserFailureReachesErrorInsteadOfHanging() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];
        new InputStreamPublisher(new ByteArrayInputStream("bad\n".getBytes(StandardCharsets.UTF_8)),
                s -> { throw new IllegalArgumentException("Invalid response"); })
                .subscribe(new Subscriber<String>() {
                    public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
                    public void onNext(String s) { fail("No content expected"); }
                    public void onError(Throwable t) { error[0] = t; done.countDown(); }
                    public void onComplete() { done.countDown(); }
                });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertTrue(error[0] instanceof IllegalArgumentException);
    }
}
