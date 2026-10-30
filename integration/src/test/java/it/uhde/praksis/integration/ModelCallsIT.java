// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * The AI module of the runtime image against model servers in the test process: a command asks a
 * model through a grant that limits the profile, so that the text goes to the local model and a
 * cloud profile is refused before anything is sent.
 */
class ModelCallsIT {
  private static final String ASK =
      """
      id = "ai.ask"
      effect = "external"
      needs = ["ai.complete"]

      [trigger]
      kind = "command"
      title = "Ask a model"
      [[trigger.param]]
      name = "prompt"
      type = "string"
      [[trigger.param]]
      name = "text"
      type = "string"
      default = ""
      [[trigger.param]]
      name = "profile"
      type = "string"
      default = ""

      [[action]]
      id = "ask"
      capability = "ai.complete"
      args = { prompt = { cel = "event.data.prompt" }, text = { cel = "event.data.text" }, profile = { cel = "event.data.profile" } }
      """;

  @Test
  void aGrantThatLimitsTheProfileLetsTheTextGoToTheLocalModelOnly() throws Exception {
    try (FakeModelServer models =
            new FakeModelServer()
                .answering(
                    r ->
                        switch (r.path()) {
                          case "/api/tags" -> new FakeModelServer.Reply(200, "{\"models\":[]}");
                          case "/api/chat" ->
                              new FakeModelServer.Reply(
                                  200,
                                  "{\"message\":{\"content\":\"Hello from the local model\"},\"prompt_eval_count\":9,\"eval_count\":4}");
                          default -> new FakeModelServer.Reply(404, "{}");
                        });
        Harness h = new Harness()) {
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

          [module.ai.cloud]
          api = "openai"
          url = "%s/v1"
          model = "big"
          data = "cloud"
          credential = "ai:cloud"
          """
              .formatted(h.mail, models.url(), models.url()));
      Files.writeString(h.home.resolve("ruleset/rules/ai.ask.toml"), ASK);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [credential."ai:cloud"]
              command = ["sh", "-c", "printf the-key"]
              capabilities = ["ai.complete"]

              [[grant]]
              id = "ask-local"
              layer = "ruleset"
              capability = "ai.complete"
              [grant.args]
              profile = { one_of = ["local"] }
              """);
      Process daemon = h.startDaemon();

      Harness.CliResult local =
          h.cli("run", "ai.ask", "prompt=Say hello.", "text=my private mail", "profile=local");
      assertThat(local.exit()).as(local.output()).isZero();
      assertThat(local.output()).contains("OK").contains("ask");
      var chat = models.to("/api/chat");
      assertThat(chat).hasSize(1);
      assertThat(chat.get(0).body().path("messages").get(1).path("content").asText())
          .startsWith("Say hello.")
          .contains("my private mail");

      // the cloud profile is not in the grant: refused, and nothing reaches any server
      Harness.CliResult cloud =
          h.cli("run", "ai.ask", "prompt=Say hello.", "text=my private mail", "profile=cloud");
      assertThat(cloud.exit()).as(cloud.output()).isEqualTo(1);
      assertThat(cloud.output()).contains("DENIED");
      assertThat(models.to("/v1/chat/completions")).isEmpty();
      assertThat(models.to("/api/chat")).hasSize(1);
      assertThat(h.cli("approvals").output()).contains("ai.complete");

      // a call without a profile is not covered by a grant that names the profile
      Harness.CliResult none = h.cli("run", "ai.ask", "prompt=Say hello.");
      assertThat(none.exit()).isEqualTo(1);
      assertThat(models.to("/api/chat")).hasSize(1);

      // the key of the cloud profile was never asked for
      assertThat(h.cli("audit", "-n", "100").output()).doesNotContain("the-key");
      h.stop(daemon);
    }
  }
}
