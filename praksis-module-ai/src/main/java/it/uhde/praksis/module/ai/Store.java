// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The log of every model call and the tokens used per day, in a SQLite file in the data directory.
 * The capabilities of the module share one instance (see {@link #open}). The log says what was
 * sent, to which profile and model, and what came back; with {@code log_content = false} it keeps a
 * hash of both instead of the text. Calls older than the retention are deleted.
 */
final class Store {
  private static final Map<Path, Store> OPEN = new HashMap<>();
  private static final int SCHEMA = 2;

  private final Path file;
  private final Connection db;
  private int users;
  private int writes;

  private Store(Path file, Connection db) {
    this.file = file;
    this.db = db;
  }

  static synchronized Store open(Path directory) throws CapabilityException {
    if (directory == null) {
      throw new CapabilityException(
          "the AI module needs a data directory, which the daemon did not give", false);
    }
    Path file = directory.resolve("ai.db");
    try {
      Store store = OPEN.get(file);
      if (store == null) {
        store = new Store(file, connect(file));
        OPEN.put(file, store);
      }
      store.users++;
      return store;
    } catch (SQLException e) {
      throw new CapabilityException(
          "cannot open the log of model calls: " + e.getMessage(), true, e);
    }
  }

  private static Connection connect(Path file) throws SQLException {
    Connection db = DriverManager.getConnection("jdbc:sqlite:" + file);
    try (Statement s = db.createStatement()) {
      s.execute("PRAGMA journal_mode = WAL");
      int version;
      try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
        version = r.next() ? r.getInt(1) : 0;
      }
      if (version == 0) {
        s.execute(
            "CREATE TABLE IF NOT EXISTS call (id INTEGER PRIMARY KEY, at TEXT NOT NULL,"
                + " capability TEXT NOT NULL, profile TEXT NOT NULL, model TEXT NOT NULL,"
                + " data TEXT NOT NULL, tokens_in INTEGER NOT NULL, tokens_out INTEGER NOT NULL,"
                + " millis INTEGER NOT NULL, outcome TEXT NOT NULL, prompt TEXT NOT NULL,"
                + " response TEXT NOT NULL, system TEXT NOT NULL DEFAULT '')");
        s.execute(
            "CREATE TABLE IF NOT EXISTS usage (day TEXT NOT NULL, profile TEXT NOT NULL,"
                + " tokens INTEGER NOT NULL, PRIMARY KEY (day, profile))");
        s.execute("PRAGMA user_version = " + SCHEMA);
      } else if (version == 1) {
        // the system message is kept apart from the rest, so that a call can be sent again
        s.execute("ALTER TABLE call ADD COLUMN system TEXT NOT NULL DEFAULT ''");
        s.execute("PRAGMA user_version = " + SCHEMA);
      } else if (version != SCHEMA) {
        throw new SQLException("the log was written by another version of the module");
      }
    }
    return db;
  }

  /** One model call as it is logged. */
  record Call(
      String capability,
      String profile,
      String model,
      String data,
      long tokensIn,
      long tokensOut,
      long millis,
      String outcome,
      String prompt,
      String response,
      String system) {}

  /** A logged call with what it takes to send it again. */
  record Stored(
      long id,
      String capability,
      String profile,
      String model,
      String system,
      String prompt,
      String response) {}

  synchronized java.util.Optional<Stored> find(long id) throws CapabilityException {
    try (PreparedStatement p =
        db.prepareStatement(
            "SELECT id, capability, profile, model, system, prompt, response FROM call WHERE id = ?")) {
      p.setLong(1, id);
      try (ResultSet r = p.executeQuery()) {
        return r.next()
            ? java.util.Optional.of(
                new Stored(
                    r.getLong(1),
                    r.getString(2),
                    r.getString(3),
                    r.getString(4),
                    r.getString(5),
                    r.getString(6),
                    r.getString(7)))
            : java.util.Optional.empty();
      }
    } catch (SQLException e) {
      throw new CapabilityException(
          "cannot read the log of model calls: " + e.getMessage(), true, e);
    }
  }

  synchronized void record(Call call, boolean content, int retentionDays)
      throws CapabilityException {
    try (PreparedStatement p =
        db.prepareStatement(
            "INSERT INTO call (at, capability, profile, model, data, tokens_in, tokens_out, millis,"
                + " outcome, prompt, response, system) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      p.setString(1, Instant.now().toString());
      p.setString(2, call.capability());
      p.setString(3, call.profile());
      p.setString(4, call.model());
      p.setString(5, call.data());
      p.setLong(6, call.tokensIn());
      p.setLong(7, call.tokensOut());
      p.setLong(8, call.millis());
      p.setString(9, call.outcome());
      p.setString(10, content ? call.prompt() : "sha256:" + sha256(call.prompt()));
      p.setString(11, content ? call.response() : "sha256:" + sha256(call.response()));
      p.setString(12, content ? call.system() : "");
      p.executeUpdate();
      if (call.tokensIn() + call.tokensOut() > 0) {
        try (PreparedStatement u =
            db.prepareStatement(
                "INSERT INTO usage (day, profile, tokens) VALUES (?,?,?) ON CONFLICT(day, profile)"
                    + " DO UPDATE SET tokens = tokens + excluded.tokens")) {
          u.setString(1, today());
          u.setString(2, call.profile());
          u.setLong(3, call.tokensIn() + call.tokensOut());
          u.executeUpdate();
        }
      }
      if (++writes % 100 == 1) {
        prune(retentionDays);
      }
    } catch (SQLException e) {
      throw new CapabilityException(
          "cannot write the log of model calls: " + e.getMessage(), true, e);
    }
  }

  synchronized long tokensToday(String profile) throws CapabilityException {
    try (PreparedStatement p =
        db.prepareStatement("SELECT tokens FROM usage WHERE day = ? AND profile = ?")) {
      p.setString(1, today());
      p.setString(2, profile);
      try (ResultSet r = p.executeQuery()) {
        return r.next() ? r.getLong(1) : 0;
      }
    } catch (SQLException e) {
      throw new CapabilityException("cannot read the budget: " + e.getMessage(), true, e);
    }
  }

  synchronized List<Map<String, Object>> calls(int limit, String profile, boolean content)
      throws CapabilityException {
    String sql =
        "SELECT id, at, capability, profile, model, data, tokens_in, tokens_out, millis, outcome,"
            + " prompt, response FROM call "
            + (profile == null ? "" : "WHERE profile = ? ")
            + "ORDER BY id DESC LIMIT ?";
    try (PreparedStatement p = db.prepareStatement(sql)) {
      int i = 1;
      if (profile != null) {
        p.setString(i++, profile);
      }
      p.setInt(i, limit);
      List<Map<String, Object>> out = new ArrayList<>();
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", r.getLong(1));
          row.put("at", r.getString(2));
          row.put("capability", r.getString(3));
          row.put("profile", r.getString(4));
          row.put("model", r.getString(5));
          row.put("data", r.getString(6));
          row.put("tokens_in", r.getLong(7));
          row.put("tokens_out", r.getLong(8));
          row.put("millis", r.getLong(9));
          row.put("outcome", r.getString(10));
          if (content) {
            row.put("prompt", r.getString(11));
            row.put("response", r.getString(12));
          }
          out.add(row);
        }
      }
      return out;
    } catch (SQLException e) {
      throw new CapabilityException(
          "cannot read the log of model calls: " + e.getMessage(), true, e);
    }
  }

  private void prune(int retentionDays) throws SQLException {
    try (PreparedStatement p = db.prepareStatement("DELETE FROM call WHERE at < ?")) {
      p.setString(1, Instant.now().minusSeconds(retentionDays * 86_400L).toString());
      p.executeUpdate();
    }
    try (PreparedStatement p = db.prepareStatement("DELETE FROM usage WHERE day < ?")) {
      p.setString(1, LocalDate.now(ZoneOffset.UTC).minusDays(35).toString());
      p.executeUpdate();
    }
  }

  static String today() {
    return LocalDate.now(ZoneOffset.UTC).toString();
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  synchronized void release() {
    if (--users == 0) {
      synchronized (Store.class) {
        OPEN.remove(file);
      }
      try {
        db.close();
      } catch (SQLException e) {
        // closing only ends the connection
      }
    }
  }
}
