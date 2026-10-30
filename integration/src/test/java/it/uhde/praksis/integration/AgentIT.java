// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The maintenance agent with the real daemon: the ruleset Praksis-Agent as it is published, the AI
 * module, a layer of the person's own with a rule that has no test, and a model server in the test
 * process. The model writes a test file; the checks of the daemon decide whether it is staged, and
 * a model that writes nonsense gets one more try with the problems and then gives up.
 */
class AgentIT {
  private static final String DEMO_RULE =
      """
      id = "mail.50-demo"
      description = "Moves mail whose subject starts with x to the folder Demo."
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"
      filter = "event.data.subject.startsWith('x')"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Demo" }
      """;

  private static final String EVENT =
      "type = \"mail.received\"\n"
          + "data = { message = \"m1\", folder = \"INBOX\", size = 100, from = \"a@example.org\", to = \"me@example.org\","
          + " subject = \"%s\", message_id = \"<1@t>\", cc = \"\", date = \"2026-01-01T10:00:00Z\", list_id = \"\","
          + " precedence = \"\", x_spam_flag = \"\" }\n";

  /** A test file that is right for the rule. */
  private static final String GOOD =
      "format_version = 1\n\n[[test]]\nname = \"a subject with x is moved to Demo\"\n[test.event]\n"
          + EVENT.formatted("x marks the spot")
          + "[[test.call]]\ncapability = \"mail.move\"\nargs = { message = \"m1\", folder = \"Demo\" }\n"
          + "\n[[test]]\nname = \"another subject is left alone\"\n[test.event]\n"
          + EVENT.formatted("hello");

  /** Valid, but no test in it makes the rule fire. */
  private static final String DOES_NOT_FIRE =
      "format_version = 1\n\n[[test]]\nname = \"hello is left alone\"\n[test.event]\n"
          + EVENT.formatted("hello");

  private static final String EVIL =
      """
      id = "agent.evil"
      effect = "local-write"
      needs = ["praksis.agent.propose"]

      [trigger]
      kind = "command"
      title = "Try to write a rule"

      [[action]]
      id = "p"
      capability = "praksis.agent.propose"
      args = { layer = "mine", rule = "mail.50-demo", files = [{ path = "rules/mail.50-demo.toml", content = "evil" }] }
      """;

  private static void copy(Path from, Path to) throws IOException {
    try (Stream<Path> all = Files.walk(from)) {
      for (Path p : (Iterable<Path>) all::iterator) {
        Path rel = from.relativize(p);
        if (rel.toString().startsWith(".git")) {
          continue;
        }
        if (Files.isDirectory(p)) {
          Files.createDirectories(to.resolve(rel));
        } else {
          Files.copy(p, to.resolve(rel));
        }
      }
    }
  }

  private static FakeModelServer.Reply say(String text) {
    return new FakeModelServer.Reply(
        200,
        "{\"message\":{\"content\":"
            + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(text)
            + "},\"prompt_eval_count\":5,\"eval_count\":5}");
  }

  /** The home: the agent layer as published, a layer of the person's own, grants, a model. */
  private static void setUp(Harness h, FakeModelServer models) throws Exception {
    Path agent = Path.of(System.getProperty("praksis.agent.ruleset")).toAbsolutePath().normalize();
    assumeTrue(Files.isRegularFile(agent.resolve("ruleset.toml")), "Praksis-Agent is needed");
    try (Stream<Path> all = Files.walk(h.home.resolve("ruleset"))) {
      all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
    copy(agent, h.home.resolve("agent-layer"));
    Path mine = h.home.resolve("mine");
    Files.createDirectories(mine.resolve("rules"));
    Files.writeString(mine.resolve("ruleset.toml"), "format_version = 1\nname = \"mine\"\n");
    Files.writeString(mine.resolve("rules/mail.50-demo.toml"), DEMO_RULE);
    Files.writeString(
        h.home.resolve("praksis.toml"),
        """
        format_version = 1
        enabled_modules = ["maildir", "ai"]

        [module.maildir]
        root = '%s'

        [module.ai]
        default = "local"

        [module.ai.local]
        api = "ollama"
        url = "%s"
        model = "m1"
        data = "local"

        [[layer]]
        name = "agent"
        path = '%s'

        [[layer]]
        name = "mine"
        path = '%s'
        """
            .formatted(h.mail, models.url(), h.home.resolve("agent-layer"), mine));
    Files.writeString(
        h.home.resolve("policy.toml"),
        """
        format_version = 1

        [[grant]]
        id = "read"
        layer = "agent"
        capability = "praksis.rules.read"
        [[grant]]
        id = "ask"
        layer = "agent"
        capability = "ai.complete"
        [grant.args]
        profile = { one_of = ["local"] }
        [[grant]]
        id = "propose"
        layer = "agent"
        capability = "praksis.agent.propose"
        [[grant]]
        id = "note"
        layer = "agent"
        capability = "praksis.notes.save"
        """);
  }

  private static String notes(Harness h) throws IOException {
    Path dir = h.home.resolve("data/praksis/notes");
    if (!Files.isDirectory(dir)) {
      return "";
    }
    try (Stream<Path> files = Files.list(dir)) {
      StringBuilder all = new StringBuilder();
      for (Path f : (Iterable<Path>) files.sorted()::iterator) {
        all.append(Files.readString(f)).append('\n');
      }
      return all.toString();
    }
  }

  private static FakeModelServer model(Function<String, String> answerFor, AtomicInteger asked)
      throws IOException {
    return new FakeModelServer()
        .answering(
            r -> {
              if (r.path().equals("/api/tags")) {
                return new FakeModelServer.Reply(200, "{\"models\":[]}");
              }
              asked.incrementAndGet();
              return say(
                  answerFor.apply(r.body().path("messages").get(1).path("content").asText()));
            });
  }

  @Test
  void aTestThatPassesTheChecksIsStagedAndIsOnlyTakenByThePerson() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    // the model wraps the file in a code fence, as models do
    try (FakeModelServer models = model(user -> "```toml\n" + GOOD + "```", asked);
        Harness h = new Harness()) {
      setUp(h, models);
      Process daemon = h.startDaemon();

      // the window asks which rules the agent can write tests for
      var untested = h.ask(new it.uhde.praksis.protocol.Request.Untested());
      assertThat(untested)
          .isEqualTo(
              new it.uhde.praksis.protocol.Response.UntestedRules(
                  java.util.List.of(
                      new it.uhde.praksis.protocol.Response.UntestedRules.Rule(
                          "mail.50-demo", "mine", "local-write"))));

      Harness.CliResult done = h.cli("run", "agent.write-tests", "rule=mail.50-demo");

      assertThat(done.exit()).as(done.output()).isZero();
      assertThat(asked.get()).isEqualTo(1);
      // the model was given the rule as data, and what fires it
      String user =
          models.to("/api/chat").get(0).body().path("messages").get(1).path("content").asText();
      assertThat(user)
          .contains("It is fired by the event mail.received with the data fields: message (string)")
          .contains("<<<data ")
          .contains("startsWith('x')");
      // staged, with the checks passed, and nothing in the layer itself
      String listed = h.cli("agent", "list").output();
      assertThat(listed)
          .contains("mine")
          .contains("mail.50-demo")
          .contains("tests/mail.50-demo.toml");
      String id = listed.split("\\s+")[0];
      assertThat(h.home.resolve("mine/tests")).doesNotExist();
      Harness.CliResult shown = h.cli("agent", "show", id);
      assertThat(shown.output())
          .contains("a subject with x is moved to Demo")
          .contains("0 error(s)");
      assertThat(notes(h)).contains("is staged as " + id);

      // the window asks the daemon for the same things as the command line
      var list =
          (it.uhde.praksis.protocol.Response.StagedList)
              h.ask(new it.uhde.praksis.protocol.Request.Staged());
      assertThat(list.items()).hasSize(1);
      assertThat(list.items().get(0).id()).isEqualTo(id);
      assertThat(list.items().get(0).layer()).isEqualTo("mine");
      assertThat(list.items().get(0).files()).containsExactly("tests/mail.50-demo.toml");
      var detail =
          (it.uhde.praksis.protocol.Response.StagedDetail)
              h.ask(new it.uhde.praksis.protocol.Request.StagedShow(id));
      assertThat(detail.files()).containsOnlyKeys("tests/mail.50-demo.toml");
      assertThat(detail.files().get("tests/mail.50-demo.toml")).startsWith("format_version = 1");
      assertThat(detail.verification().errors()).isZero();
      assertThat(h.ask(new it.uhde.praksis.protocol.Request.StagedShow("nope")))
          .isInstanceOf(it.uhde.praksis.protocol.Response.Error.class);

      // the person takes it into their own checkout, and only there
      Path checkout = Files.createDirectories(h.home.resolve("checkout"));
      Harness.CliResult exported = h.cli("agent", "export", id, checkout.toString());
      assertThat(exported.exit()).as(exported.output()).isZero();
      assertThat(Files.readString(checkout.resolve("tests/mail.50-demo.toml")))
          .startsWith("format_version = 1");
      assertThat(h.cli("agent", "export", id, checkout.toString()).exit())
          .isEqualTo(1); // not over a file
      assertThat(h.ask(new it.uhde.praksis.protocol.Request.StagedDrop(id)))
          .isEqualTo(new it.uhde.praksis.protocol.Response.Done("Dropped " + id + "."));
      assertThat(h.cli("agent", "list").output()).contains("Nothing is staged");
      assertThat(h.home.resolve("mine/tests"))
          .doesNotExist(); // nothing of it was ever in the layer
      h.stop(daemon);
    }
  }

  @Test
  void aModelThatFailsTheChecksGetsTheProblemsAndOneMoreTry() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    try (FakeModelServer models =
            model(user -> user.contains("--- the problems ---") ? GOOD : DOES_NOT_FIRE, asked);
        Harness h = new Harness()) {
      setUp(h, models);
      Process daemon = h.startDaemon();

      Harness.CliResult done = h.cli("run", "agent.write-tests", "rule=mail.50-demo");

      assertThat(done.exit()).as(done.output()).isZero();
      assertThat(asked.get()).isEqualTo(2);
      String second =
          models.to("/api/chat").get(1).body().path("messages").get(1).path("content").asText();
      assertThat(second)
          .contains("--- your earlier file ---")
          .contains("no test makes the rule fire");
      assertThat(h.cli("agent", "list").output()).contains("mail.50-demo");
      assertThat(notes(h)).contains("is staged as");
      h.stop(daemon);
    }
  }

  @Test
  void aModelThatKeepsWritingNonsenseStagesNothingAndTheNoteSaysWhy() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    try (FakeModelServer models = model(user -> "this is [not a test {{", asked);
        Harness h = new Harness()) {
      setUp(h, models);
      Process daemon = h.startDaemon();

      Harness.CliResult done = h.cli("run", "agent.write-tests", "rule=mail.50-demo");

      assertThat(done.exit())
          .as(done.output())
          .isZero(); // the rule did its job: it tried and said so
      assertThat(asked.get()).isEqualTo(2);
      assertThat(h.cli("agent", "list").output()).contains("Nothing is staged");
      assertThat(notes(h)).contains("No test file was staged for mail.50-demo");
      h.stop(daemon);
    }
  }

  @Test
  void aProposalCanOnlyAddATestFileAndOnlyToALayerOfTheRulesOwner() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    try (FakeModelServer models = model(user -> GOOD, asked);
        Harness h = new Harness()) {
      setUp(h, models);
      Files.writeString(h.home.resolve("agent-layer/rules/agent.evil.toml"), EVIL);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "evil"
              layer = "agent"
              capability = "praksis.agent.propose"
              """);
      Process daemon = h.startDaemon();

      Harness.CliResult evil = h.cli("run", "agent.evil");

      assertThat(evil.exit()).isEqualTo(1);
      assertThat(h.cli("log", "-n", "10").output()).contains("may only add test files");
      assertThat(h.cli("agent", "list").output()).contains("Nothing is staged");
      assertThat(h.home.resolve("mine/rules/mail.50-demo.toml")).hasContent(DEMO_RULE);
      h.stop(daemon);
    }
  }
}
