// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A model server in the test process: plain sockets, so that it needs no JDK module. */
final class FakeModelServer implements AutoCloseable {
  /** What came in. */
  record Request(String method, String path, Map<String, String> headers, JsonNode body) {}

  /** What goes out. */
  record Reply(int status, String json, Map<String, String> headers) {
    Reply(int status, String json) {
      this(status, json, Map.of());
    }
  }

  private final ServerSocket socket;
  final List<Request> requests = new CopyOnWriteArrayList<>();
  private volatile Function<Request, Reply> handler = r -> new Reply(404, "{}");

  FakeModelServer() throws IOException {
    socket = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                while (true) {
                  Socket client = socket.accept();
                  Thread.ofVirtual().start(() -> serve(client));
                }
              } catch (IOException closed) {
                // the test is over
              }
            });
  }

  FakeModelServer answering(Function<Request, Reply> handler) {
    this.handler = handler;
    return this;
  }

  String url() {
    return "http://127.0.0.1:" + socket.getLocalPort();
  }

  List<Request> to(String path) {
    return requests.stream().filter(r -> r.path().equals(path)).toList();
  }

  private void serve(Socket client) {
    try (client) {
      InputStream in = client.getInputStream();
      String head = readHead(in);
      String[] first = head.split("\r\n")[0].split(" ");
      Map<String, String> headers = new java.util.LinkedHashMap<>();
      for (String line : head.split("\r\n")) {
        int colon = line.indexOf(':');
        if (colon > 0) {
          headers.put(
              line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
        }
      }
      int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
      byte[] body = in.readNBytes(length);
      JsonNode json =
          length == 0
              ? Http.JSON.nullNode()
              : Http.JSON.readTree(new String(body, StandardCharsets.UTF_8));
      Request request = new Request(first[0], first[1], headers, json);
      requests.add(request);
      Reply reply = handler.apply(request);
      byte[] out = reply.json().getBytes(StandardCharsets.UTF_8);
      StringBuilder response =
          new StringBuilder(
              "HTTP/1.1 " + reply.status() + " X\r\nContent-Type: application/json\r\n");
      reply.headers().forEach((k, v) -> response.append(k).append(": ").append(v).append("\r\n"));
      response
          .append("Content-Length: ")
          .append(out.length)
          .append("\r\nConnection: close\r\n\r\n");
      client.getOutputStream().write(response.toString().getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().write(out);
      client.getOutputStream().flush();
    } catch (IOException e) {
      // the client went away
    }
  }

  private static String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int state = 0;
    while (state < 4) {
      int b = in.read();
      if (b < 0) {
        break;
      }
      head.write(b);
      state =
          (b == '\r' && (state == 0 || state == 2))
              ? state + 1
              : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
    }
    return head.toString(StandardCharsets.UTF_8);
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
