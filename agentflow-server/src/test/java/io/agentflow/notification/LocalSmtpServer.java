package io.agentflow.notification;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 仅监听回环的实际 SMTP 协议夹具，不向外部邮件服务发送测试数据。 @author owlzhangfq@gmail.com */
final class LocalSmtpServer implements AutoCloseable {
    enum Mode { ACCEPT, REJECT_RECIPIENT_TEMPORARY, REJECT_RECIPIENT_PERMANENT, REJECT_DATA_TEMPORARY, REJECT_DATA_PERMANENT, DISCONNECT_AFTER_DATA, WAIT_AFTER_DATA, AUTH_REJECT }
    private final ServerSocket server;
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Mode mode;
    private volatile Socket client;
    private volatile boolean stopping;
    private volatile Throwable failure;
    final List<String> commands = new CopyOnWriteArrayList<>();
    final List<String> messages = new CopyOnWriteArrayList<>();

    LocalSmtpServer(Mode mode) throws Exception {
        this.mode = mode; server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        executor.submit(() -> {
            while (!stopping) {
                try (var socket = server.accept()) { client = socket; socket.setSoTimeout(5000); exchange(socket); }
                catch (Exception error) { if (!stopping) { failure = error; return; } }
                finally { client = null; }
            }
        });
    }
    int port() { return server.getLocalPort(); }
    private void exchange(Socket socket) throws Exception {
        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        var output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
        reply(output, "220 test.example.invalid ESMTP");
        String line;
        while ((line = input.readLine()) != null) {
            commands.add(line);
            if (line.startsWith("EHLO")) { reply(output, "250-test.example.invalid"); reply(output, mode == Mode.AUTH_REJECT ? "250 AUTH PLAIN LOGIN" : "250 8BITMIME"); }
            else if (line.startsWith("HELO")) reply(output, "250 test.example.invalid");
            else if (line.startsWith("AUTH")) reply(output, "535 Authentication rejected");
            else if (line.startsWith("MAIL FROM")) reply(output, "250 Sender accepted");
            else if (line.startsWith("RCPT TO")) reply(output, mode == Mode.REJECT_RECIPIENT_TEMPORARY ? "451 Recipient temporary rejection"
                    : mode == Mode.REJECT_RECIPIENT_PERMANENT ? "550 Recipient rejection" : "250 Recipient accepted");
            else if (line.equals("DATA")) {
                reply(output, "354 End with dot"); var body = new StringBuilder();
                while ((line = input.readLine()) != null && !line.equals(".")) body.append(line).append("\r\n");
                messages.add(body.toString());
                if (mode == Mode.DISCONNECT_AFTER_DATA) return;
                if (mode == Mode.WAIT_AFTER_DATA) { closed.await(8, TimeUnit.SECONDS); return; }
                reply(output, mode == Mode.REJECT_DATA_TEMPORARY ? "451 Message rejected temporarily"
                        : mode == Mode.REJECT_DATA_PERMANENT ? "550 Message rejected" : "250 Message accepted");
            } else if (line.equals("QUIT")) { reply(output, "221 Closing"); return; }
            else reply(output, "250 Reset");
        }
    }
    private static void reply(PrintWriter output, String line) { output.print(line + "\r\n"); output.flush(); }
    @Override public void close() throws Exception {
        stopping = true; closed.countDown(); server.close(); var connection = client; if (connection != null) connection.close();
        executor.shutdownNow(); if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("SMTP fixture did not stop");
        if (failure != null) throw new AssertionError("SMTP fixture failed", failure);
    }
}
