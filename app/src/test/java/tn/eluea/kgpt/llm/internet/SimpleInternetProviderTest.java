package tn.eluea.kgpt.llm.internet;

import org.junit.Test;
import java.io.*;
import java.net.*;
import tn.eluea.kgpt.llm.service.InternetRequestListener;
import static org.junit.Assert.*;

public class SimpleInternetProviderTest {
    private static final InternetRequestListener LISTENER = new InternetRequestListener() {
        public void onRequestStatusCode(int code) { }
        public void onRequestComplete() { }
    };
    private static class Connection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream input = new ByteArrayInputStream("reply".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean disconnected;
        Connection() throws Exception { super(new URL("https://example.invalid")); }
        public void connect() { }
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
        public OutputStream getOutputStream() { return output; }
        public int getResponseCode() { return 200; }
        public InputStream getInputStream() { return input; }
    }
    @Test public void streamingFailureIsNotConvertedToSuccess() throws Exception {
        Connection connection = new Connection();
        connection.input = new InputStream() { public int read() throws IOException { throw new IOException("connection lost"); } };
        try (InputStream input = new SimpleInternetProvider().sendRequest(connection, "prompt", LISTENER)) {
            try { input.read(); fail("Stream failure was swallowed"); }
            catch (IOException expected) { assertEquals("connection lost", expected.getMessage()); }
        }
        assertTrue(connection.disconnected);
    }
    @Test public void stopDisconnectsAnActiveConnection() throws Exception {
        Connection connection = new Connection(); RequestCancellation request = new RequestCancellation();
        RequestCancellation.bind(request);
        try (InputStream input = new SimpleInternetProvider().sendRequest(connection, "prompt", LISTENER)) {
            request.cancel(); assertTrue(connection.disconnected);
        } finally { RequestCancellation.unbind(); }
    }
    @Test public void cancelledRequestNeverStartsNetworkWork() throws Exception {
        Connection connection = new Connection(); RequestCancellation request = new RequestCancellation();
        request.cancel(); RequestCancellation.bind(request);
        try {
            try { new SimpleInternetProvider().sendRequest(connection, "prompt", LISTENER); fail(); }
            catch (IOException expected) { assertEquals(0, connection.output.size()); }
        } finally { RequestCancellation.unbind(); }
    }
}
