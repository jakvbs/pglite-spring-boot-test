/**
 * Helper entry-point that exposes a PGlite TCP endpoint for the Spring test suite.
 *
 * The script uses pg-gateway to wrap PGlite with a PostgreSQL wire protocol server,
 * so that the Java configuration only needs to launch a long-running process and wait
 * until a single JSON READY message is printed on stdout.
 */

import { PGlite } from '@electric-sql/pglite';
import net from 'node:net';
import { md5 } from 'pg-gateway';
import { fromNodeSocket } from 'pg-gateway/node';

const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = 0;
const SERVER_VERSION = '16.3 (PGlite 0.2.0)';

function loadUserCatalog(log) {
  const defaults = { postgres: '' };
  const raw = process.env.PGLITE_USERS_JSON;
  if (!raw) {
    return defaults;
  }
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') {
      return defaults;
    }
    const result = { ...defaults };
    for (const [user, password] of Object.entries(parsed)) {
      if (typeof user !== 'string' || user.trim() === '') {
        continue;
      }
      result[user] = typeof password === 'string' ? password : '';
    }
    return result;
  } catch (err) {
    if (typeof log === "function" && log('ERROR')) {
      console.error(`Failed to parse PGLITE_USERS_JSON: ${err.message}`);
    }
    return defaults;
  }
}

const connectionSecretKeyMap = new WeakMap();

function getEnvDefault(name, defaultValue) {
  const value = process.env[name];
  return (value !== undefined && value !== '') ? value : defaultValue;
}

function pickPort(host) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, host, () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
    server.on('error', reject);
  });
}

async function main() {
  const host = getEnvDefault('PGLITE_HOST', DEFAULT_HOST);
  const portEnv = getEnvDefault('PGLITE_PORT', DEFAULT_PORT.toString());
  let port;

  try {
    port = parseInt(portEnv, 10);
  } catch (err) {
    console.error(`Invalid PGLITE_PORT value: ${portEnv}`);
    process.exit(2);
  }

  if (port <= 0) {
    try {
      port = await pickPort(host);
    } catch (err) {
      console.error(`Failed to allocate port: ${err.message}`);
      process.exit(3);
    }
  }

  const logLevel = getEnvDefault('PGLITE_LOG_LEVEL', 'WARNING').toUpperCase();

  const shouldLog = (level) => {
    const levels = { DEBUG: 0, INFO: 1, WARNING: 2, ERROR: 3 };
    return levels[level] >= levels[logLevel];
  };

  const validUsers = loadUserCatalog(shouldLog);

  let db;
  try {
    db = new PGlite();
    await db.waitReady;
    if (shouldLog('INFO')) {
      console.error('PGlite instance ready');
    }
  } catch (err) {
    console.error(`Failed to initialize PGlite: ${err.message}`);
    process.exit(4);
  }

  const server = net.createServer(async (socket) => {
    if (shouldLog('DEBUG')) {
      console.error(`New client connection from ${socket.remoteAddress}:${socket.remotePort}`);
    }

    let connection;

    try {
      connection = await fromNodeSocket(socket, {
        serverVersion: SERVER_VERSION,
        auth: {
          method: 'md5',
          async getPreHashedPassword({ username }) {
            if (shouldLog('DEBUG')) {
              console.error(`Generating pre-hash for user: ${username}`);
            }
            const password = validUsers[username];
            if (password === undefined) {
              if (shouldLog('DEBUG')) {
                console.error(`Unknown user during pre-hash lookup: ${username}`);
              }
              // Return deterministic hash to keep timings consistent; the validator will reject later.
              return await md5(username);
            }
            return await md5(password + username);
          },
        },
        async onAuthenticated() {
          if (!connection?.streamWriter) {
            return;
          }

          const extras = [];
          extras.push(connection.createParameterStatus('client_encoding', 'UTF8'));
          extras.push(connection.createParameterStatus('DateStyle', 'ISO, MDY'));
          extras.push(connection.createParameterStatus('integer_datetimes', 'on'));

          const secret = connectionSecretKeyMap.get(connection) ?? generateSecretKey();
          connectionSecretKeyMap.set(connection, secret);
          const backendKey = createBackendKeyData(process.pid, secret);
          extras.push(backendKey);

          for (const payload of extras) {
            await connection.streamWriter.write(payload);
          }
        },
        async onStartup(state) {
          const user = state.clientParams?.user ?? '<unknown>';
          if (shouldLog('DEBUG')) {
            console.error(`Startup received for user: ${user}`);
          }
          await db.waitReady;
          return false;
        },
        async onMessage(data, state) {
          if (!state.isAuthenticated) {
            return undefined;
          }

          try {
            return await handleFrontendMessageBuffered(connection, data, db, shouldLog);
          } catch (err) {
            if (shouldLog('ERROR')) {
              console.error(`Error executing protocol: ${err.message}`);
            }
            return undefined;
          }
        },
      });
      if (shouldLog('DEBUG')) {
        console.error(`Connection created with auth method: ${connection.options.auth.method}`);
      }
      const secretKey = generateSecretKey();
      connectionSecretKeyMap.set(connection, secretKey);
    } catch (err) {
      if (shouldLog('ERROR')) {
        console.error(`Failed to create Postgres connection: ${err.message}`);
      }
      socket.destroy();
      return;
    }

    socket.on('end', () => {
      if (shouldLog('DEBUG')) {
        console.error('Client disconnected');
      }
    });

    socket.on('error', (err) => {
      if (shouldLog('ERROR')) {
        console.error(`Socket error: ${err.message}`);
      }
    });

    socket.on('close', () => {
    });
  });

  server.listen(port, host, () => {
    const readyPayload = {
      event: 'READY',
      host: host,
      port: port,
      pid: process.pid,
    };
    console.log(JSON.stringify(readyPayload));

    if (shouldLog('INFO')) {
      console.error(`PGlite server listening on ${host}:${port}`);
    }
  });

  server.on('error', (err) => {
    console.error(`Server error: ${err.message}`);
    process.exit(5);
  });

  const shutdown = async (signal) => {
    if (shouldLog('INFO')) {
      console.error(`Received ${signal}, shutting down...`);
    }

    server.close(() => {
      if (shouldLog('INFO')) {
        console.error('Server closed');
      }
    });

    if (db) {
      try {
        await db.close();
        if (shouldLog('INFO')) {
          console.error('PGlite closed');
        }
      } catch (err) {
        console.error(`Error closing PGlite: ${err.message}`);
        process.exit(6);
      }
    }

    process.exit(0);
  };

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

const CODE_Q = 'Q'.charCodeAt(0);
const CODE_P = 'P'.charCodeAt(0);
const CODE_B = 'B'.charCodeAt(0);
const CODE_D = 'D'.charCodeAt(0);
const CODE_E = 'E'.charCodeAt(0);
const CODE_S = 'S'.charCodeAt(0);
const CODE_H = 'H'.charCodeAt(0);
const CODE_X = 'X'.charCodeAt(0);

const pendingFrontendQueue = new WeakMap();

function queueFor(conn) {
  let q = pendingFrontendQueue.get(conn);
  if (!q) {
    q = [];
    pendingFrontendQueue.set(conn, q);
  }
  return q;
}

async function handleFrontendMessageBuffered(connection, data, db, shouldLog) {
  const code = data[0] | 0;
  if (code === CODE_X) return undefined; // let base close
  if (code === CODE_Q) {
    const raw = await db.execProtocolRaw(data);
    return raw && raw.length ? [raw] : [];
  }
  if (code === CODE_P || code === CODE_B || code === CODE_D || code === CODE_E) {
    queueFor(connection).push(Buffer.from(data));
    return []; // handled, no fallback
  }
  if (code === CODE_S || code === CODE_H) {
    const q = queueFor(connection);
    q.push(Buffer.from(data));
    const payload = Buffer.concat(q);
    q.length = 0;
    const raw = await db.execProtocolRaw(payload);
    return raw && raw.length ? [raw] : [];
  }
  return undefined; // unknown, let base decide
}

function extractPayload(entry) {
  if (!entry) {
    return undefined;
  }
  if (entry instanceof Uint8Array) {
    return entry;
  }
  if (Array.isArray(entry)) {
    const candidate = entry[1];
    return candidate instanceof Uint8Array ? candidate : undefined;
  }
  if (entry.data instanceof Uint8Array) {
    return entry.data;
  }
  return undefined;
}

function generateSecretKey() {
  return Math.floor(Math.random() * 0x7fffffff);
}

function createBackendKeyData(pid, secretKey) {
  const buffer = Buffer.alloc(13);
  buffer[0] = 0x4b; // 'K'
  buffer.writeInt32BE(12, 1);
  const pidValue = (pid ?? process.pid) | 0;
  const key = (secretKey ?? generateSecretKey()) | 0;
  buffer.writeInt32BE(pidValue, 5);
  buffer.writeInt32BE(key, 9);
  return buffer;
}

main().catch((err) => {
  console.error(`Fatal error: ${err.message}`);
  console.error(err.stack);
  process.exit(1);
});

process.on('unhandledRejection', (reason) => {
  console.error('Unhandled rejection:', reason);
});
