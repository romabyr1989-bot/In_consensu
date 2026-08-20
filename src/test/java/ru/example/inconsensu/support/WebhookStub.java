package ru.example.inconsensu.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Подставной потребитель webhook на обычном сокете.
 *
 * <p>Ни библиотеки-мока, ни {@code com.sun.net.httpserver}: первая требует ADR по §14.8, вторая запрещена
 * правилами Checkstyle. Здесь нужно ровно одно — принять POST, запомнить заголовки и тело, ответить кодом.
 */
public final class WebhookStub implements AutoCloseable {

    /** @param headers заголовки запроса в нижнем регистре: подпись и тип события проверяются по ним */
    public record Received(Map<String, String> headers, String body) {}

    private final ServerSocket server;
    private final Thread acceptor;
    private final List<Received> received = new ArrayList<>();
    private final AtomicInteger responseCode;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public WebhookStub(int responseCode) {
        this.responseCode = new AtomicInteger(responseCode);
        try {
            server = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось поднять подставной приёмник webhook", e);
        }
        acceptor = new Thread(this::acceptLoop, "webhook-stub");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/hook";
    }

    public void respondWith(int code) {
        responseCode.set(code);
    }

    public List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            server.close();
        } catch (IOException ignored) {
            // Закрытие уже закрытого сокета в тесте ничего не меняет.
        }
        acceptor.interrupt();
    }

    private void acceptLoop() {
        while (running.get()) {
            try (Socket socket = server.accept()) {
                handle(socket);
            } catch (IOException e) {
                if (running.get()) {
                    throw new IllegalStateException("Подставной приёмник webhook упал", e);
                }
                return;
            }
        }
    }

    /**
     * Заголовки читаются построчно, тело — байтами.
     *
     * <p>{@code Content-Length} задан в байтах, а не в символах. Раньше тело вычитывалось из
     * {@code Reader} тем же числом символов: на латинице это совпадало, а на кириллице символов меньше,
     * чем байтов, — стаб ждал недостающие, не отвечал вовсе, и отправитель обрывал запрос по таймауту.
     * Поэтому здесь ровно один поток байтов, а декодирование — уже после чтения.
     */
    /** Строка запроса и заголовки — ASCII; читаются побайтно, чтобы не буферизовать тело. */
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        int current;
        while ((current = in.read()) >= 0) {
            if (current == '\n') {
                break;
            }
            if (current != '\r') {
                line.write(current);
            }
        }
        if (current < 0 && line.size() == 0) {
            return null;
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();

        String requestLine = readLine(in);
        if (requestLine == null) {
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int chunk = in.read(body, read, length - read);
            if (chunk < 0) {
                break;
            }
            read += chunk;
        }
        synchronized (received) {
            received.add(new Received(headers, new String(body, 0, Math.max(read, 0), StandardCharsets.UTF_8)));
        }

        int code = responseCode.get();
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + code + " \r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
