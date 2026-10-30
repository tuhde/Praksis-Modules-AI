// SPDX-License-Identifier: Apache-2.0
/**
 * Model access: {@code ai.complete}, {@code ai.classify}, {@code ai.extract} and {@code ai.embed}.
 */
module it.uhde.praksis.module.ai {
  requires it.uhde.praksis.capability;
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires java.sql;
  requires org.xerial.sqlitejdbc;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.ai.AiCalls,
      it.uhde.praksis.module.ai.AiClassify,
      it.uhde.praksis.module.ai.AiComplete,
      it.uhde.praksis.module.ai.AiEmbed,
      it.uhde.praksis.module.ai.AiEvaluate,
      it.uhde.praksis.module.ai.AiExtract;
}
