// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai.extract}: the values of named fields (a date, an amount, a person) found in a text.
 * Every value is text, and a field that is not in the text is empty; nothing the model makes up for
 * a field outside the list gets through.
 */
public final class AiExtract extends Base {
  @Override
  public String name() {
    return "ai.extract";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    String text = Args.text(args, "text", true);
    List<String> fields = Args.names(args, "fields", 1, 20, 32, "[a-z][a-z0-9_]*");
    String instructions = Args.text(args, "instructions", false);
    Runner runner = runner();
    Profile profile = runner.profile(Args.text(args, "profile", false));

    ObjectNode schema = Http.JSON.createObjectNode().put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    ArrayNode required = schema.putArray("required");
    for (String f : fields) {
      properties.putObject(f).putArray("type").add("string").add("null");
      required.add(f);
    }
    schema.put("additionalProperties", false);

    String instruction =
        "Find in the data the values of these fields: "
            + String.join(", ", fields)
            + ". "
            + (instructions == null ? "" : instructions + " ")
            + "Answer with one JSON object that has exactly these fields, each a text copied or"
            + " normalised from the data, or null if the data does not say.";
    Runner.Done done = runner.chat(context, name(), profile, null, instruction, text, schema, 512);

    Map<String, Object> values = new LinkedHashMap<>();
    JsonNode parsed = context.dryRun() ? null : parse(done.reply().text());
    int found = 0;
    for (String f : fields) {
      String value =
          parsed != null && parsed.path(f).isTextual() ? parsed.get(f).asText().strip() : "";
      if (value.length() > 2_000) {
        value = value.substring(0, 2_000);
      }
      values.put(f, value);
      if (!value.isEmpty()) {
        found++;
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("values", values);
    result.put("found", (long) found);
    return with(result, done);
  }

  private static JsonNode parse(String text) {
    try {
      return Http.JSON.readTree(text.strip());
    } catch (IOException e) {
      return null;
    }
  }
}
