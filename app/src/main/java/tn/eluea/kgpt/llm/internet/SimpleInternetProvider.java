package tn.eluea.kgpt.llm.internet;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import tn.eluea.kgpt.llm.service.InternetRequestListener;

/** Read failures reach the subscriber instead of being converted into successful EOF. */
public class SimpleInternetProvider implements InternetProvider {
    @Override
    public InputStream sendRequest(HttpURLConnection connection, String body,
                                   InternetRequestListener listener) throws IOException {
        RequestCancellation cancellation = RequestCancellation.current();
        Runnable disconnect = connection::disconnect;
        if (cancellation != null) cancellation.register(disconnect);
        try {
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            listener.onRequestStatusCode(status);
            if (status < 200 || status >= 300) {
                String error = "";
                InputStream stream = connection.getErrorStream();
                if (stream != null) {
                    try (InputStream input = stream) {
                        byte[] bytes = new byte[8192];
                        int count = input.read(bytes);
                        if (count > 0) error = new String(bytes, 0, count, StandardCharsets.UTF_8);
                    }
                }
                throw new IOException("API Error " + status + ": " + error);
            }
            return new FilterInputStream(connection.getInputStream()) {
                @Override public void close() throws IOException {
                    try { super.close(); }
                    finally {
                        connection.disconnect();
                        if (cancellation != null) cancellation.unregister(disconnect);
                    }
                }
            };
        } catch (IOException | RuntimeException error) {
            connection.disconnect();
            if (cancellation != null) cancellation.unregister(disconnect);
            throw error;
        }
    }
}
