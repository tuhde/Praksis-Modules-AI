// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The speech module of the runtime image with the Vosk of the person (if it is set up) and a voice
 * made by espeak-ng that plays the part of the microphone: a command listens, and the note of what
 * was said is a mail in a folder. A grant limits the profile and the folder.
 */
class SpeechIT {
  private static final String NOTE =
      """
      id = "speech.note"
      effect = "local-write"
      needs = ["speech.listen", "mail.append"]

      [trigger]
      kind = "command"
      title = "Make a note by voice"

      [[action]]
      id = "listen"
      capability = "speech.listen"
      args = { seconds = 20, profile = "en" }

      [[action]]
      id = "save"
      capability = "mail.append"
      when = { cel = "results.listen.heard" }
      args = { folder = "Voice", seen = true, content = { cel = "bytes('Subject: Voice note\\\\n\\\\n' + results.listen.text + '\\\\n')" } }
      """;

  private static void run(String... command) throws Exception {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
    assertThat(p.waitFor()).as(String.join(" ", command)).isZero();
  }

  @Test
  void whatIsSaidIntoTheMicrophoneBecomesANoteThroughTheGrants() throws Exception {
    Path vosk = Path.of(System.getProperty("user.home"), ".local/share/vosk-dictation");
    Path python = vosk.resolve("venv/bin/python");
    Path model = vosk.resolve("models/vosk-model-small-en-us-0.15");
    assumeTrue(Files.isExecutable(python) && Files.isDirectory(model), "no Vosk set up at " + vosk);
    assumeTrue(Files.isExecutable(Path.of("/usr/bin/espeak-ng")), "needs espeak-ng");
    try (Harness h = new Harness()) {
      Path wav = h.home.resolve("said.wav");
      Path raw = h.home.resolve("said.raw");
      run(
          "espeak-ng",
          "-v",
          "en-us",
          "-s",
          "140",
          "-w",
          wav.toString(),
          "buy milk and bread tomorrow");
      run(
          "ffmpeg",
          "-nostdin",
          "-v",
          "error",
          "-i",
          wav.toString(),
          "-f",
          "s16le",
          "-ar",
          "16000",
          "-ac",
          "1",
          raw.toString());
      Files.writeString(
          h.home.resolve("praksis.toml"),
          """
          format_version = 1
          enabled_modules = ["maildir", "speech"]

          [module.maildir]
          root = '%s'

          [module.speech]
          python = ["%s"]
          record_command = ["cat", "%s"]

          [module.speech.en]
          model = "%s"
          language = "en"
          """
              .formatted(h.mail, python, raw, model));
      Files.writeString(h.home.resolve("ruleset/rules/speech.note.toml"), NOTE);
      Files.writeString(
          h.home.resolve("policy.toml"),
          """
          format_version = 1

          [[grant]]
          id = "listen"
          layer = "ruleset"
          capability = "speech.listen"
          [grant.args]
          profile = { one_of = ["en"] }

          [[grant]]
          id = "note"
          layer = "ruleset"
          capability = "mail.append"
          [grant.args]
          folder = { one_of = ["Voice"] }
          """);
      Process daemon = h.startDaemon();

      Harness.CliResult done = h.cli("run", "speech.note");

      assertThat(done.exit()).as(done.output()).isZero();
      assertThat(done.output()).contains("listen: speech.listen").contains("save: mail.append");
      assertThat(h.names(".Voice/cur")).hasSize(1);
      String note =
          Files.readString(h.mail.resolve(".Voice/cur").resolve(h.names(".Voice/cur").get(0)));
      assertThat(note).contains("Subject: Voice note").contains("bread").contains("tomorrow");
      h.stop(daemon);
    }
  }
}
