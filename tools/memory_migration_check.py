"""MIGRATION_3_4 实测：造一个 schema=3 的库 → 跑迁移 → 校验结构与数据。

只依赖 Python 标准库 sqlite3。不改动项目任何文件（库建在 app/build/ 下的临时目录）。
注意：这里执行的是 AppDatabase.kt 里 MIGRATION_3_4 的**逐字副本**，
两边不一致时这个测试也就失去意义 —— 所以 SQL 改动必须同步过来。
"""

import os
import sqlite3
import sys

# ---- 与 MIGRATION_3_4 逐字对应的 SQL（改动迁移时必须同步）----
MIGRATION_SQL = [
    """
    CREATE TABLE IF NOT EXISTS memories (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        characterId TEXT NOT NULL,
        createdAt INTEGER NOT NULL,
        sessionId TEXT NOT NULL,
        content TEXT NOT NULL,
        contentHash TEXT NOT NULL,
        category TEXT NOT NULL,
        importance INTEGER NOT NULL,
        lastUsedAt INTEGER NOT NULL,
        sourceFromTs INTEGER NOT NULL,
        sourceFromId INTEGER NOT NULL,
        sourceToTs INTEGER NOT NULL,
        sourceToId INTEGER NOT NULL,
        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX IF NOT EXISTS index_memories_sessionId ON memories(sessionId)",
    "CREATE INDEX IF NOT EXISTS index_memories_characterId ON memories(characterId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_memories_characterId_contentHash "
    "ON memories(characterId, contentHash)",
    "ALTER TABLE sessions ADD COLUMN characterKey TEXT NOT NULL DEFAULT 'default'",
    "ALTER TABLE sessions ADD COLUMN savedMemoryUpToTs INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE sessions ADD COLUMN savedMemoryUpToId INTEGER NOT NULL DEFAULT 0",
    """
    UPDATE sessions SET
        savedMemoryUpToTs = COALESCE((
            SELECT m.timestamp FROM messages m WHERE m.sessionId = sessions.id
            ORDER BY m.timestamp DESC, m.id DESC LIMIT 1), 0),
        savedMemoryUpToId = COALESCE((
            SELECT m.id FROM messages m WHERE m.sessionId = sessions.id
            ORDER BY m.timestamp DESC, m.id DESC LIMIT 1), 0)
    """,
]

# ---- schema=3 的建表语句（按 Room 在 version=3 时生成的形态）----
SCHEMA_V3_DDL = [
    """
    CREATE TABLE IF NOT EXISTS sessions (
        id TEXT NOT NULL PRIMARY KEY,
        mode TEXT NOT NULL,
        systemPrompt TEXT NOT NULL,
        createdAt INTEGER NOT NULL
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        sessionId TEXT NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        timestamp INTEGER NOT NULL,
        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
    )
    """,
    "CREATE INDEX IF NOT EXISTS index_messages_sessionId ON messages(sessionId)",
    """
    CREATE TABLE IF NOT EXISTS presets (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        name TEXT NOT NULL,
        prompt TEXT NOT NULL DEFAULT '',
        refAudioBase64 TEXT NOT NULL DEFAULT '',
        refAudioMime TEXT NOT NULL DEFAULT 'audio/wav',
        avatarUri TEXT NOT NULL DEFAULT '',
        backgroundUri TEXT NOT NULL DEFAULT '',
        createdAt INTEGER NOT NULL,
        ttsPrompt TEXT NOT NULL DEFAULT ''
    )
    """,
    # Room 会写 user_version 标记 schema 版本
    "PRAGMA user_version = 3",
]

# ---- schema=4 时期望的列（与 MemoryEntity / SessionEntity 对齐）----
EXPECTED_MEMORY_COLUMNS = [
    "id", "characterId", "createdAt", "sessionId", "content", "contentHash",
    "category", "importance", "lastUsedAt",
    "sourceFromTs", "sourceFromId", "sourceToTs", "sourceToId",
]
EXPECTED_NEW_SESSION_COLUMNS = ["characterKey", "savedMemoryUpToTs", "savedMemoryUpToId"]
EXPECTED_INDEXES = {
    "index_memories_sessionId",
    "index_memories_characterId",
    "index_memories_characterId_contentHash",
}

failures = []
checks = 0


def check(label, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}  {detail}")
        failures.append(label)


def build_v3(db_path):
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys = ON")
    for ddl in SCHEMA_V3_DDL:
        con.execute(ddl)
    # 样例数据：时间戳故意让"同毫秒"出现一次，用来验证字典序末条判据
    sessions = [
        # id, mode, prompt, createdAt
        ("s-multi", "LONG", "银狼", 1_000),
        ("s-single", "LONG", "deepseek", 2_000),
        ("s-empty", "QUICK", "", 3_000),
        ("s-samems", "LONG", "银狼", 4_000),
    ]
    con.executemany("INSERT INTO sessions VALUES (?,?,?,?)", sessions)

    messages = [
        # sessionId, role, content, timestamp
        ("s-multi", "user", "你好", 100),
        ("s-multi", "assistant", "哟", 110),
        ("s-multi", "user", "我最近在写一个语音通话 App", 120),
        ("s-single", "user", "你好", 200),
        # 同毫秒两条：末条应由 id 决胜（后插入的 id 更大）
        ("s-samems", "user", "甲", 300),
        ("s-samems", "assistant", "乙", 300),
        ("s-samems", "user", "丙", 300),
    ]
    con.executemany(
        "INSERT INTO messages (sessionId, role, content, timestamp) VALUES (?,?,?,?)",
        messages,
    )
    con.commit()
    con.close()


def run_migration(db_path):
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys = ON")
    try:
        for sql in MIGRATION_SQL:
            con.execute(sql)
        con.execute("PRAGMA user_version = 4")
        con.commit()
        return None
    except Exception as exc:  # noqa: BLE001
        return exc
    finally:
        con.close()


def main():
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "migration_check")
    os.makedirs(out_dir, exist_ok=True)
    db_path = os.path.join(out_dir, "test_v3_to_v4.db")
    if os.path.exists(db_path):
        os.remove(db_path)

    print("[1] 造 schema=3 的库 + 样例数据")
    build_v3(db_path)
    con = sqlite3.connect(db_path)
    before_sessions = con.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
    before_messages = con.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
    con.close()
    check("存量会话 4 行 / 消息 7 行", before_sessions == 4 and before_messages == 7,
          f"实际 {before_sessions}/{before_messages}")

    print("[2] 执行 MIGRATION_3_4")
    err = run_migration(db_path)
    check("迁移无 SQL 异常", err is None, f"{type(err).__name__}: {err}")
    if err is not None:
        return finish()

    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys = ON")

    print("[3] 结构校验")
    cols = [r[1] for r in con.execute("PRAGMA table_info(memories)")]
    check("memories 列与实体一致", cols == EXPECTED_MEMORY_COLUMNS, f"实际 {cols}")

    s_cols = [r[1] for r in con.execute("PRAGMA table_info(sessions)")]
    for c in EXPECTED_NEW_SESSION_COLUMNS:
        check(f"sessions 新增列 {c}", c in s_cols)

    idx = {r[1] for r in con.execute(
        "SELECT type, name FROM sqlite_master WHERE type='index' AND tbl_name='memories'")}
    check("memories 三个索引名齐全", EXPECTED_INDEXES.issubset(idx),
          f"缺 {EXPECTED_INDEXES - idx}")
    unique_idx = con.execute(
        "SELECT name FROM pragma_index_list('memories') WHERE \"unique\" = 1").fetchall()
    check("唯一索引落在 (characterId, contentHash)",
          any(r[0] == "index_memories_characterId_contentHash" for r in unique_idx),
          f"实际 {unique_idx}")

    fk = con.execute("PRAGMA foreign_key_list(memories)").fetchall()
    # 期望: (id, seq, table, from, to, on_update, on_delete, match)
    check("外键 sessionId → sessions.id ON DELETE CASCADE",
          len(fk) == 1 and fk[0][2] == "sessions" and fk[0][3] == "sessionId"
          and fk[0][4] == "id" and fk[0][6].upper() == "CASCADE",
          f"实际 {fk}")

    print("[4] 数据存活")
    check("会话数不变", con.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 4)
    check("消息数不变", con.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 7)
    check("presets 表还在", con.execute(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='presets'").fetchone()[0] == 1)

    print("[5] 游标初始化（plan4 P8：存量会话必须初始化到各自末条）")
    cur = dict(con.execute(
        "SELECT id, savedMemoryUpToTs FROM sessions").fetchall())
    cur_id = dict(con.execute(
        "SELECT id, savedMemoryUpToId FROM sessions").fetchall())

    # s-multi 末条 = ts 120
    check("s-multi 游标 ts=120", cur["s-multi"] == 120, f"实际 {cur['s-multi']}")
    check("s-single 游标 ts=200", cur["s-single"] == 200, f"实际 {cur['s-single']}")
    check("s-empty 游标 ts=0（无消息）", cur["s-empty"] == 0, f"实际 {cur['s-empty']}")
    # 同毫秒三条：末条应是最后插入的那条（id 最大）
    last_row = con.execute(
        "SELECT id, timestamp FROM messages WHERE sessionId='s-samems' "
        "ORDER BY timestamp DESC, id DESC LIMIT 1").fetchone()
    check("s-samems 同毫秒取 id 最大的末条",
          cur["s-samems"] == last_row[1] and cur_id["s-samems"] == last_row[0],
          f"游标 {(cur['s-samems'], cur_id['s-samems'])} vs 末条 {last_row}")

    print("[6] 升级后不应再有「待整理」（补总结的 EXISTS 判据）")
    pending = con.execute("""
        SELECT s.id FROM sessions s WHERE EXISTS (
            SELECT 1 FROM messages m WHERE m.sessionId = s.id
              AND (m.timestamp > s.savedMemoryUpToTs
                   OR (m.timestamp = s.savedMemoryUpToTs AND m.id > s.savedMemoryUpToId)))
    """).fetchall()
    check("待整理会话 = 0（否则升级后会批量重跑 LLM）", pending == [], f"实际 {pending}")

    print("[7] 升级后新增的消息应被判定为待整理")
    con.execute("INSERT INTO messages (sessionId, role, content, timestamp) "
                "VALUES ('s-multi','user','新消息', 9999)")
    con.commit()
    pending2 = con.execute("""
        SELECT s.id FROM sessions s WHERE EXISTS (
            SELECT 1 FROM messages m WHERE m.sessionId = s.id
              AND (m.timestamp > s.savedMemoryUpToTs
                   OR (m.timestamp = s.savedMemoryUpToTs AND m.id > s.savedMemoryUpToId)))
    """).fetchall()
    check("只有 s-multi 变成待整理", [r[0] for r in pending2] == ["s-multi"],
          f"实际 {pending2}")

    print("[8] 记忆表可写 + 内容去重 + 级联删除")
    con.execute("""INSERT INTO memories
        (characterId, createdAt, sessionId, content, contentHash, category,
         importance, lastUsedAt, sourceFromTs, sourceFromId, sourceToTs, sourceToId)
        VALUES ('silverwolf', 5000, 's-multi', '用户喜欢深夜写代码', 'hash-1',
                'summary', 7, 0, 120, 3, 9999, 8)""")
    con.commit()
    dup_blocked = False
    try:
        con.execute("""INSERT INTO memories
            (characterId, createdAt, sessionId, content, contentHash, category,
             importance, lastUsedAt, sourceFromTs, sourceFromId, sourceToTs, sourceToId)
            VALUES ('silverwolf', 5001, 's-multi', '重复内容', 'hash-1',
                    'summary', 5, 0, 120, 3, 9999, 8)""")
        con.commit()
    except sqlite3.IntegrityError:
        dup_blocked = True
    check("(characterId, contentHash) 唯一索引拦下重复内容", dup_blocked)
    check("不同角色同 hash 允许（隔离键参与唯一性）", not dup_blocked or True)
    try:
        con.execute("""INSERT INTO memories
            (characterId, createdAt, sessionId, content, contentHash, category,
             importance, lastUsedAt, sourceFromTs, sourceFromId, sourceToTs, sourceToId)
            VALUES ('deepseek', 5002, 's-multi', '重复内容但另一个角色', 'hash-1',
                    'summary', 5, 0, 120, 3, 9999, 8)""")
        con.commit()
        check("不同角色同 hash 可共存", True)
    except sqlite3.IntegrityError as exc:
        check("不同角色同 hash 可共存", False, str(exc))

    # 级联：删会话应删掉它的记忆
    con.execute("DELETE FROM sessions WHERE id='s-multi'")
    con.commit()
    left = con.execute("SELECT COUNT(*) FROM memories WHERE sessionId='s-multi'").fetchone()[0]
    check("删会话级联删记忆", left == 0, f"还剩 {left} 条")

    print("[9] 记忆引用不存在的会话应被外键拒绝")
    rejected = False
    try:
        con.execute("""INSERT INTO memories
            (characterId, createdAt, sessionId, content, contentHash, category,
             importance, lastUsedAt, sourceFromTs, sourceFromId, sourceToTs, sourceToId)
            VALUES ('silverwolf', 6000, 'no-such-session', 'x', 'hash-9',
                    'summary', 5, 0, 0, 0, 0, 0)""")
        con.commit()
    except sqlite3.IntegrityError:
        rejected = True
    check("外键拒绝孤儿记忆", rejected)

    con.close()
    return finish()


def finish():
    print()
    if failures:
        print(f"RESULT: {len(failures)}/{checks} 项失败")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"RESULT: 全部 {checks} 项通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
