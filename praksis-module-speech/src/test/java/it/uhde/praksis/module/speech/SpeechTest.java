// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpeechTest {
  @TempDir Path dir;

  private static CapabilityContext context(boolean dry) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dry;
      }

      @Override
      public String idempotencyKey() {
        return "k";
      }

      @Override
      public Optional<Event> event() {
        return Optional.empty();
      }
    };
  }

  private Path script(String name, String body) throws Exception {
    Path file = dir.resolve(name);
    Files.writeString(file, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    return file;
  }

  private Map<String, Object> settings(List<String> python, List<String> record, Object... more)
      throws Exception {
    Path model = Files.createDirectories(dir.resolve("model-en"));
    Map<String, Object> en = new LinkedHashMap<>();
    en.put("model", model.toString());
    en.put("language", "en");
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("python", python);
    if (record != null) {
      s.put("record_command", record);
    }
    s.put("en", en);
    for (int i = 0; i < more.length; i += 2) {
      s.put((String) more[i], more[i + 1]);
    }
    return s;
  }

  private <T extends Base> T make(T capability, Map<String, Object> settings) throws Exception {
    capability.configure(settings);
    Path uploads = Files.createDirectories(dir.resolve("uploads"));
    capability.useUploadsDirectory(uploads);
    return capability;
  }

  /** Stands in for the recognizer: says what it was given, as vosk.py does. */
  private Path fakeVosk() throws Exception {
    return script(
        "fake-vosk.sh",
        "echo '{\"ready\": true}'\nn=$(wc -c)\nprintf '{\"text\": \"got %s bytes in mode %s\"}\\n' \"$n\" \"$4\"");
  }

  @Test
  void aSentenceEndsTheListeningAtOnceAndTheMicrophoneIsSwitchedOff() throws Exception {
    // the recognizer is done after the first chunk, as when vosk.py has heard a sentence
    Path vosk =
        script(
            "first.sh",
            "echo '{\"ready\": true}'\nhead -c 100 > /dev/null\necho '{\"text\": \"turn on the lights\"}'");
    Path stopped = dir.resolve("microphone-stopped");
    // an endless microphone, which says when it is killed
    Path mic =
        script(
            "mic.sh",
            "trap 'touch "
                + stopped
                + "; exit 0' TERM\nwhile :; do printf 'aaaaaaaaaaaaaaaaaaaa'; sleep 0.01; done");
    SpeechListen listen =
        make(new SpeechListen(), settings(List.of("sh", vosk.toString()), List.of(mic.toString())));

    long started = System.nanoTime();
    Map<String, Object> result = listen.invoke(context(false), Map.of("seconds", 20));

    assertThat(result)
        .containsEntry("text", "turn on the lights")
        .containsEntry("heard", true)
        .containsEntry("profile", "en")
        .containsEntry("language", "en");
    assertThat((System.nanoTime() - started) / 1e9)
        .as("it did not wait for the seconds")
        .isLessThan(10.0);
  }

  @Test
  void aSilentMicrophoneEndsWhenTheSecondsAreOverWithNoText() throws Exception {
    Path vosk =
        script("silent.sh", "echo '{\"ready\": true}'\ncat > /dev/null\necho '{\"text\": \"\"}'");
    Path mic = script("quiet.sh", "sleep 100");
    SpeechListen listen =
        make(new SpeechListen(), settings(List.of("sh", vosk.toString()), List.of(mic.toString())));

    long started = System.nanoTime();
    Map<String, Object> result = listen.invoke(context(false), Map.of("seconds", 2));

    assertThat(result).containsEntry("text", "").containsEntry("heard", false);
    assertThat((System.nanoTime() - started) / 1e9).isBetween(1.5, 12.0);
  }

  @Test
  void aFileIsTranscribedByItsTokenAndAnythingElseIsRefused() throws Exception {
    Path vosk = fakeVosk();
    Path convert = script("convert.sh", "cat \"$1\"");
    Map<String, Object> s =
        settings(
            List.of("sh", vosk.toString()),
            null,
            "convert_command",
            List.of(convert.toString(), "{file}"));
    SpeechTranscribe t = make(new SpeechTranscribe(), s);
    String token = UUID.randomUUID().toString();
    Files.writeString(dir.resolve("uploads").resolve(token + ".part"), "twelve bytes");

    assertThat(t.invoke(context(false), Map.of("file", token)))
        .containsEntry("text", "got 12 bytes in mode all")
        .containsEntry("heard", true);

    for (String bad :
        new String[] {"../../etc/passwd", "/etc/passwd", token + ".part", "not-a-token", ""}) {
      assertThatThrownBy(() -> t.invoke(context(false), Map.of("file", bad)))
          .as(bad)
          .hasMessageContaining("token of an uploaded file");
    }
    assertThatThrownBy(() -> t.invoke(context(false), Map.of("file", UUID.randomUUID().toString())))
        .hasMessageContaining("is not there");
    // a link in the uploads directory is not followed
    String link = UUID.randomUUID().toString();
    Files.createSymbolicLink(
        dir.resolve("uploads").resolve(link + ".part"), Path.of("/etc/passwd"));
    assertThatThrownBy(() -> t.invoke(context(false), Map.of("file", link)))
        .hasMessageContaining("is not there");
  }

  @Test
  void aDryRunSwitchesNothingOn() throws Exception {
    Path marker = dir.resolve("started");
    Path mic = script("mic.sh", "touch " + marker);
    SpeechListen listen =
        make(new SpeechListen(), settings(List.of("true"), List.of(mic.toString())));

    assertThat(listen.invoke(context(true), Map.of()))
        .containsEntry("text", "")
        .containsEntry("heard", false);
    assertThat(marker).doesNotExist();
  }

  @Test
  void whatGoesWrongIsSaidInPlainWords() throws Exception {
    SpeechListen noPython =
        make(
            new SpeechListen(),
            settings(List.of(dir.resolve("no-python").toString()), List.of("true")));
    assertThatThrownBy(() -> noPython.invoke(context(false), Map.of()))
        .hasMessageContaining("cannot run");
    Path broken =
        script("broken.sh", "echo 'ModuleNotFoundError: No module named vosk' >&2\nexit 1");
    SpeechListen noVosk =
        make(new SpeechListen(), settings(List.of("sh", broken.toString()), List.of("true")));
    assertThatThrownBy(() -> noVosk.invoke(context(false), Map.of()))
        .hasMessageContaining("No module named vosk");
    Path vosk = fakeVosk();
    SpeechListen noMic =
        make(
            new SpeechListen(),
            settings(List.of("sh", vosk.toString()), List.of(dir.resolve("no-mic").toString())));
    assertThatThrownBy(() -> noMic.invoke(context(false), Map.of()))
        .hasMessageContaining("cannot run");
  }

  @Test
  void theSettingsAreCheckedWhenTheyAreRead() throws Exception {
    SpeechListen l = new SpeechListen();
    Path model = Files.createDirectories(dir.resolve("m"));
    Map<String, Object> en = Map.of("model", model.toString());
    assertThatThrownBy(() -> l.configure(Map.of("bogus", 1)))
        .hasMessageContaining("unknown speech setting 'bogus'");
    assertThatThrownBy(
            () -> l.configure(Map.of("en", Map.of("model", dir.resolve("none").toString()))))
        .hasMessageContaining("is not there");
    assertThatThrownBy(() -> l.configure(Map.of("en", Map.of("bogus", "x"))))
        .hasMessageContaining("unknown setting 'bogus' in profile 'en'");
    assertThatThrownBy(() -> l.configure(Map.of("en", Map.of("language", "en"))))
        .hasMessageContaining("'model' is required");
    assertThatThrownBy(() -> l.configure(Map.of("python", "python3", "en", en)))
        .hasMessageContaining("'python' must be a command");
    assertThatThrownBy(() -> l.configure(Map.of("convert_command", List.of("ffmpeg"), "en", en)))
        .hasMessageContaining("needs an argument {file}");
    assertThatThrownBy(() -> l.configure(Map.of("max_seconds", 0, "en", en)))
        .hasMessageContaining("'max_seconds'");
    assertThatThrownBy(() -> l.configure(Map.of("default", "de", "en", en)))
        .hasMessageContaining("names the profile 'de'");
    l.configure(Map.of("en", en, "max_seconds", 60));
    assertThatThrownBy(() -> l.invoke(context(false), Map.of("seconds", 61)))
        .hasMessageContaining("from 1 to 60");
    assertThatThrownBy(() -> l.invoke(context(false), Map.of("profile", "xx")))
        .hasMessageContaining("no profile 'xx'");
  }

  // ------------------------------------------------------------------------------------------

  /** The real thing: the Vosk of the person, and a voice made by espeak-ng, if they are there. */
  @Test
  void theRealVoskUnderstandsAnEspeakVoiceFromAFileAndFromAMicrophone() throws Exception {
    Path vosk =
        Path.of(
            System.getProperty(
                "praksis.vosk", System.getProperty("user.home") + "/.local/share/vosk-dictation"));
    Path python = vosk.resolve("venv/bin/python");
    Path model = vosk.resolve("models/vosk-model-small-en-us-0.15");
    assumeTrue(Files.isExecutable(python) && Files.isDirectory(model), "no Vosk set up at " + vosk);
    assumeTrue(
        Files.isExecutable(Path.of("/usr/bin/espeak-ng"))
            && Files.isExecutable(Path.of("/usr/bin/ffmpeg")),
        "needs espeak-ng and ffmpeg");
    Path wav = dir.resolve("said.wav");
    Path raw = dir.resolve("said.raw");
    run(
        "espeak-ng",
        "-v",
        "en-us",
        "-s",
        "140",
        "-w",
        wav.toString(),
        "turn on the lights in the kitchen please");
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

    Map<String, Object> s = new LinkedHashMap<>();
    s.put("python", List.of(python.toString()));
    s.put(
        "record_command", List.of("cat", raw.toString())); // a microphone that plays the recording
    s.put("en", Map.of("model", model.toString(), "language", "en"));
    SpeechTranscribe transcribe = make(new SpeechTranscribe(), s);
    SpeechListen listen = make(new SpeechListen(), s);
    String token = UUID.randomUUID().toString();
    Files.copy(wav, dir.resolve("uploads").resolve(token + ".part"));

    Map<String, Object> fromFile = transcribe.invoke(context(false), Map.of("file", token));
    Map<String, Object> fromMicrophone = listen.invoke(context(false), Map.of("seconds", 20));

    assertThat(fromFile.get("text").toString()).contains("turn on").contains("kitchen");
    assertThat(fromMicrophone.get("text").toString()).contains("turn on").contains("kitchen");
    assertThat(fromFile).containsEntry("heard", true).containsEntry("language", "en");
  }

  private static void run(String... command) throws Exception {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
    assertThat(p.waitFor()).as(String.join(" ", command)).isZero();
  }
}
