package com.penpot.android;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 PostgreSQL wire protocol 客户端，仅用于执行 CREATE DATABASE。
 * 纯 Java 实现，无 JDBC / 外部库依赖——App 环境里 psql 缺 libreadline，
 * 而且 BE 连接串指向 penpot 库，全新 initdb 后该库不存在，必须在启动 BE 前建好。
 */
public final class PgClient {
    private static final int CONNECT_TIMEOUT = 8000;

    private PgClient() {}

    /** 确保 penpot 数据库存在（幂等：已存在则忽略 duplicate_database 42P04）。 */
    public static void ensureDatabase(String host, int port, String user, String dbName) throws Exception {
        // PG 刚启动时可能短暂返回 57P03 (the database system is starting up)，
        // 即便日志已出现 ready 也可能处于该窗口——加重试最多 20×500ms=10s。
        Exception last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                ensureDatabaseOnce(host, port, user, dbName);
                return;
            } catch (Exception e) {
                last = e;
                String m = String.valueOf(e.getMessage());
                if (m.contains("57P03") || m.contains("starting up") || m.contains("Connection refused") || m.contains("Connect")) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    private static void ensureDatabaseOnce(String host, int port, String user, String dbName) throws Exception {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            sock.setSoTimeout(CONNECT_TIMEOUT);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            // --- StartupMessage: length + protocol 3.0 + key/value params + \0 ---
            StringBuilder params = new StringBuilder();
            params.append("user\0").append(user).append("\0");
            params.append("database\0").append("postgres\0"); // 连默认库执行 CREATE DATABASE
            params.append("\0");
            byte[] p = params.toString().getBytes(StandardCharsets.UTF_8);
            int startupLen = 4 + 4 + p.length; // length 自身 + protocol + params
            writeInt32(out, startupLen);
            writeInt32(out, 196608); // 3.0 << 16
            out.write(p);
            out.flush();

            // --- 读启动响应直到 ReadyForQuery ('Z') ---
            readUntilReady(in);

            // --- Simple Query: 'Q' + len + sql + \0 ---
            String sql = "CREATE DATABASE " + dbName + ";";
            byte[] qb = sql.getBytes(StandardCharsets.UTF_8);
            out.write('Q');
            writeInt32(out, 4 + qb.length + 1);
            out.write(qb);
            out.write(0);
            out.flush();

            // --- 读查询响应：捕获 ErrorResponse；42P04(duplicate) 视为成功 ---
            String errorSqlState = readQueryResult(in);
            if (errorSqlState != null && !"42P04".equals(errorSqlState)) {
                throw new IOException("CREATE DATABASE failed, SQLSTATE=" + errorSqlState);
            }
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    /** 读消息直到 ReadyForQuery，处理认证（trust 模式应直接 AuthenticationOk）。 */
    private static void readUntilReady(InputStream in) throws IOException {
        while (true) {
            int type = in.read();
            if (type < 0) throw new IOException("unexpected EOF during startup");
            int len = readInt32(in);
            byte[] payload = new byte[len - 4];
            readFully(in, payload);
            if (type == 'R') {
                int code = readInt32(new java.io.ByteArrayInputStream(payload));
                if (code != 0) throw new IOException("unsupported auth code " + code + " (need trust)");
            } else if (type == 'E') {
                throw new IOException("startup ErrorResponse: " + new String(payload, StandardCharsets.UTF_8));
            } else if (type == 'Z') {
                return;
            }
            // S/K/N 等忽略
        }
    }

    /** 读查询响应直到 ReadyForQuery，返回首个 ErrorResponse 的 SQLSTATE（无错返回 null）。 */
    private static String readQueryResult(InputStream in) throws IOException {
        String sqlState = null;
        while (true) {
            int type = in.read();
            if (type < 0) throw new IOException("unexpected EOF during query");
            int len = readInt32(in);
            byte[] payload = new byte[len - 4];
            readFully(in, payload);
            if (type == 'E') {
                // ErrorResponse: 若干 "类型码+值\0" 字段，以 \0 结束；SQLSTATE 是类型 'C' 字段（值恰好 5 字符）
                String s = new String(payload, StandardCharsets.UTF_8);
                if (sqlState == null) {
                    String[] fields = s.split("\0");
                    for (String f : fields) {
                        if (f.length() == 6 && f.charAt(0) == 'C') {
                            sqlState = f.substring(1);
                            break;
                        }
                    }
                }
            } else if (type == 'Z') {
                return sqlState;
            }
            // C(CommandComplete)/D/T/N 忽略
        }
    }

    private static void writeInt32(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int readInt32(InputStream in) throws IOException {
        int a = in.read(), b = in.read(), c = in.read(), d = in.read();
        if (a < 0 || b < 0 || c < 0 || d < 0) throw new IOException("EOF reading int32");
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("EOF reading payload");
            off += n;
        }
    }
}
