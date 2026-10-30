// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.capability.CapabilityException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the recognizer: a Python with the vosk package, started with arguments and never through a
 * shell, fed raw audio from another process (the microphone, or a converter for a file). The model
 * is loaded first, and only then does the audio start, so the seconds a call may listen are not
 * used up by loading.
 */
final class Recognizer {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_TEXT = 20_000;
  private static final long LOAD_SECONDS = 120;

  /** What was heard, and how long it took. */
  record Heard(String text, double seconds) {}

  private Recognizer() {}

  /**
   * Listens until a sentence has been said or the seconds are over.
   *
   * @param audio the command that gives the audio
   * @param seconds how long to listen at most
   */
  static Heard listen(Settings settings, Settings.Profile profile, int seconds)
      throws CapabilityException {
    return run(settings, profile, settings.recordCommand(), "first", seconds);
  }

  /** Recognizes all the speech in an audio file. */
  static Heard transcribe(Settings settings, Settings.Profile profile, java.nio.file.Path file)
      throws CapabilityException {
    List<String> command = new ArrayList<>();
    for (String part : settings.convertCommand()) {
      command.add(part.equals("{file}") ? file.toString() : part);
    }
    return run(settings, profile, command, "all", (int) TimeUnit.HOURS.toSeconds(1));
  }

  private static Heard run(
      Settings settings, Settings.Profile profile, List<String> audio, String mode, int seconds)
      throws CapabilityException {
    long started = System.nanoTime();
    Process vosk = null;
    Process source = null;
    try {
      List<String> command = new ArrayList<>(settings.python());
      command.add("-c");
      command.add(script());
      command.add(profile.model().toString());
      command.add(mode);
      try {
        vosk = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.PIPE).start();
      } catch (IOException e) {
        throw new CapabilityException(
            "cannot run '" + settings.python().get(0) + "': " + e.getMessage(), false, e);
      }
      Process recognizer = vosk;
      StringBuilder errors = new StringBuilder();
      Thread.ofVirtual().start(() -> drain(recognizer.getErrorStream(), errors));
      BufferedReader out =
          new BufferedReader(new InputStreamReader(vosk.getInputStream(), StandardCharsets.UTF_8));

      String first = out.readLine(); // {"ready": true} once the model is loaded
      if (first == null || !vosk.isAlive() && vosk.exitValue() != 0) {
        throw failed(settings, vosk, errors, "did not start");
      }
      try {
        source = new ProcessBuilder(audio).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      } catch (IOException e) {
        throw new CapabilityException(
            "cannot run '" + audio.get(0) + "': " + e.getMessage(), false, e);
      }
      source.getOutputStream().close();
      pump(source, vosk, TimeUnit.SECONDS.toNanos(seconds));

      String last = null;
      for (String line = out.readLine(); line != null; line = out.readLine()) {
        last = line;
      }
      if (!vosk.waitFor(30, TimeUnit.SECONDS)) {
        throw new CapabilityException("the recognizer did not finish", true);
      }
      if (last == null || vosk.exitValue() != 0) {
        throw failed(settings, vosk, errors, "failed");
      }
      JsonNode answer = JSON.readTree(last);
      String text = answer.path("text").asText("").strip();
      if (text.length() > MAX_TEXT) {
        text = text.substring(0, MAX_TEXT);
      }
      return new Heard(text, (System.nanoTime() - started) / 1e9);
    } catch (IOException e) {
      throw new CapabilityException("speech recognition failed: " + e.getMessage(), true, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CapabilityException("speech recognition was interrupted", true, e);
    } finally {
      if (source != null) {
        source.destroyForcibly();
      }
      if (vosk != null) {
        vosk.destroyForcibly();
      }
    }
  }

  /**
   * Copies audio into the recognizer until the audio ends, the time is over, or the recognizer has
   * what it wanted. The source is stopped at the end, which ends the copying and closes the input
   * of the recognizer, so that it gives its last result.
   */
  private static void pump(Process source, Process vosk, long nanos) throws InterruptedException {
    Thread copier =
        Thread.ofVirtual()
            .start(
                () -> {
                  byte[] buffer = new byte[8000];
                  try (InputStream in = source.getInputStream();
                      OutputStream to = vosk.getOutputStream()) {
                    for (int n; (n = in.read(buffer)) >= 0; ) {
                      to.write(buffer, 0, n);
                    }
                  } catch (IOException e) {
                    // the recognizer closed its input (it has heard a sentence), or the source
                    // ended
                  }
                });
    long end = System.nanoTime() + nanos;
    while (copier.isAlive() && vosk.isAlive() && System.nanoTime() < end) {
      copier.join(java.time.Duration.ofMillis(50));
    }
    // what the source started (a shell script's children) keeps its output open: stop all of it
    source.descendants().forEach(ProcessHandle::destroyForcibly);
    source.destroyForcibly();
    copier.join(java.time.Duration.ofSeconds(2));
    try {
      vosk.getOutputStream().close(); // whatever happened to the copier, the recognizer is done
    } catch (IOException e) {
      // it has ended already
    }
  }

  private static CapabilityException failed(
      Settings settings, Process vosk, StringBuilder errors, String what) {
    String detail = errors.toString().strip();
    String line = detail.isEmpty() ? "" : detail.lines().reduce((a, b) -> b).orElse("");
    return new CapabilityException(
        "the recognizer ("
            + settings.python().get(0)
            + ") "
            + what
            + (line.isEmpty() ? "" : ": " + (line.length() > 200 ? line.substring(0, 200) : line)),
        false);
  }

  private static void drain(InputStream in, StringBuilder into) {
    try (in) {
      byte[] b = in.readNBytes(8192);
      into.append(new String(b, StandardCharsets.UTF_8));
      in.transferTo(OutputStream.nullOutputStream());
    } catch (IOException e) {
      // nothing more to read
    }
  }

  private static String script() throws IOException {
    try (InputStream in = Recognizer.class.getResourceAsStream("vosk.py")) {
      if (in == null) {
        throw new IOException("the module has no vosk.py");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
