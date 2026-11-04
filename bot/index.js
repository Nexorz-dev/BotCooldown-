
const { Client, GatewayIntentBits, Partials } = require('discord.js');
const express = require('express');
const { Pool } = require('pg');
const crypto = require('crypto');
const { findRejectedAttemptIndex } = require('./pending-attempts');

const app = express();
app.use(express.json({ limit: '20kb' }));

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: process.env.DATABASE_URL
    ? { rejectUnauthorized: false }
    : false
});

const client = new Client({
  intents: [
    GatewayIntentBits.Guilds,
    GatewayIntentBits.GuildMessages,
    GatewayIntentBits.DirectMessages,
    GatewayIntentBits.MessageContent
  ],
  partials: [Partials.Channel]
});

const OWNER_DISCORD_ID = '401898628748279810';

const cooldowns = {
  // K-BOT publica k!d con 15 minutos; k!drop se conserva como alias.
  'k!drop': { regular: 900, vip: 900, megavip: 900, ultravip: 900 },
  'k!d': { regular: 900, vip: 900, megavip: 900, ultravip: 900 },
  'k!daily': 86400,
  'k!weekly': 604800,

  // Comandos con reducción oficial por K-PASS.
  'k!megadrop': { regular: 7200, vip: 4800, megavip: 3600, ultravip: 1800 },
  'k!md': { regular: 7200, vip: 4800, megavip: 3600, ultravip: 1800 },
  'k!lucky card': { regular: 43200, vip: 32400, megavip: 25200, ultravip: 18000 },
  'k!l': { regular: 43200, vip: 32400, megavip: 25200, ultravip: 18000 },
  'k!album drop': { regular: 14400, vip: 10800, megavip: 7200, ultravip: 3600 },
  'k!ad': { regular: 14400, vip: 10800, megavip: 7200, ultravip: 3600 },
  'k!album megadrop': { regular: 43200, vip: 36000, megavip: 28800, ultravip: 21600 },
  'k!amd': { regular: 43200, vip: 36000, megavip: 28800, ultravip: 21600 },

  // Actividades K-PASS: tiempos oficiales por nivel (en segundos).
  'k!stamp drop': 3600,
  'k!sd': 3600,
  'k!mine': { regular: 4200, vip: 3600, megavip: 3000, ultravip: 2400 },
  'k!m': { regular: 4200, vip: 3600, megavip: 3000, ultravip: 2400 },
  'k!fish': { regular: 2400, vip: 1800, megavip: 1500, ultravip: 1200 },
  'k!f': { regular: 2400, vip: 1800, megavip: 1500, ultravip: 1200 },
  'k!work': { regular: 1200, vip: 960, megavip: 780, ultravip: 600 },
  'k!w': { regular: 1200, vip: 960, megavip: 780, ultravip: 600 }
};

function getCooldownDuration(command, tier) {
  const configured = cooldowns[command];
  if (typeof configured === 'number') return configured;
  if (!configured) return 0;
  return configured[tier] || configured.regular || 0;
}

function normalizeKPassTier(value) {
  const normalized = String(value || '').trim().toLowerCase()
    .replace(/[\s_-]/g, '');
  const tiers = {
    '1': 'regular', regular: 'regular', normal: 'regular',
    '2': 'vip', vip: 'vip',
    '3': 'megavip', megavip: 'megavip',
    '4': 'ultravip', ultravip: 'ultravip'
  };
  return tiers[normalized] || null;
}

const pendingAttempts = [];
const registrationSessions = new Map();
const rejectionWindowMs = 15000;
const linkCodeLifetimeMs = 10 * 60 * 1000;

function hash(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function generateCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let code = '';

  for (let i = 0; i < 8; i++) {
    code += chars[crypto.randomInt(chars.length)];
  }

  return code.slice(0, 4) + '-' + code.slice(4);
}

function generateToken() {
  return crypto.randomBytes(32).toString('hex');
}

function validInstallationId(value) {
  return typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

async function initializeDatabase() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS botcooldown_devices (
      installation_id TEXT PRIMARY KEY,
      installation_secret_hash TEXT NOT NULL,
      discord_user_id TEXT UNIQUE,
      api_token TEXT UNIQUE,
      link_code_hash TEXT UNIQUE,
      code_expires_at BIGINT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      linked_at TIMESTAMPTZ
    )
  `);

  await pool.query(`
    ALTER TABLE botcooldown_devices
    ADD COLUMN IF NOT EXISTS kpass_tier TEXT NOT NULL DEFAULT 'regular'
  `);

  await pool.query(`
    CREATE TABLE IF NOT EXISTS botcooldown_events (
      id BIGSERIAL PRIMARY KEY,
      discord_user_id TEXT NOT NULL,
      command TEXT NOT NULL,
      duration BIGINT NOT NULL,
      started_at BIGINT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `);

  await pool.query(`
    ALTER TABLE botcooldown_events
    ADD COLUMN IF NOT EXISTS source_message_id TEXT
  `);

  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_botcooldown_events_source_message
    ON botcooldown_events (source_message_id)
    WHERE source_message_id IS NOT NULL
  `);

  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_botcooldown_events_user
    ON botcooldown_events (discord_user_id, started_at DESC)
  `);

  console.log('Tablas PostgreSQL preparadas.');
}

function getMessageText(message) {
  const embedText = message.embeds
    .map(embed => [
      embed.title,
      embed.description,
      ...(embed.fields || []).flatMap(field => [
        field.name,
        field.value
      ]),
      embed.footer?.text
    ].filter(Boolean).join('\n'))
    .join('\n');

  return `${message.content || ''}\n${embedText}`.toLowerCase();
}

async function unlinkDiscordUser(discordUserId) {
  await pool.query(`
    UPDATE botcooldown_devices
    SET discord_user_id = NULL,
        api_token = NULL,
        link_code_hash = NULL,
        code_expires_at = NULL,
        linked_at = NULL
    WHERE discord_user_id = $1
  `, [discordUserId]);
}

async function handleDirectMessage(message) {
  const userId = message.author.id;
  const content = message.content.trim();

  if (content.toLowerCase() === 'b!help') {
    await message.reply(
      '🤖 **BotCooldown - Ayuda**\n\n' +
      '• `b!register`: vincular tu cuenta con la aplicación.\n' +
      '• `b!unlink`: desvincular tu cuenta.\n' +
      '• `b!vip`: consultar o cambiar tu K-PASS.\n' +
      '• `b!help`: mostrar esta ayuda.\n\n' +
      'Para vincularte, primero solicita un código desde la aplicación y después envía `b!register` aquí.'
    );
    return;
  }

  if (content.toLowerCase() === 'b!register') {
    registrationSessions.set(userId, { step: 'code', startedAt: Date.now() });

    await message.reply(
      '🔗 **Vinculación con BotCooldown**\n\n' +
      'Ahora envíame el código de 9 caracteres que aparece en tu aplicación.\n\n' +
      'El código caduca en 10 minutos y solo se puede utilizar una vez.\n\n' +
      'Envía `cancelar` si deseas detener el proceso.'
    );
    return;
  }

  if (content.toLowerCase() === 'b!unlink') {
    await unlinkDiscordUser(userId);
    registrationSessions.delete(userId);

    await message.reply(
      '🔓 Tu cuenta de Discord ha sido desvinculada de BotCooldown. ' +
      'La aplicación dejará de recibir nuevos temporizadores de esta cuenta.'
    );
    return;
  }

  if (content.toLowerCase() === 'b!vip') {
    const linkedAccount = await pool.query(`
      SELECT kpass_tier FROM botcooldown_devices
      WHERE discord_user_id = $1
    `, [userId]);

    if (linkedAccount.rowCount === 0) {
      await message.reply('Primero vincula tu cuenta con BotCooldown usando `b!register`.');
      return;
    }
    registrationSessions.set(userId, { step: 'tier', startedAt: Date.now() });
    await message.reply(
      '💳 **Selecciona tu K-PASS actual**\n\n' +
      '1️⃣ Regular (sin VIP)\n2️⃣ VIP\n3️⃣ MEGAVIP\n4️⃣ ULTRAVIP\n\n' +
      'Responde con el número o el nombre del nivel. Escribe `cancelar` para salir.'
    );
    return;
  }

  if (content.toLowerCase() === 'cancelar') {
    registrationSessions.delete(userId);
    await message.reply('Proceso cancelado. Si ya vinculaste tu cuenta, seguirá vinculada.');
    return;
  }

  const session = registrationSessions.get(userId);

  if (session && session.step === 'tier') {
    const tier = normalizeKPassTier(content);
    if (!tier) {
      await message.reply('No reconocí ese nivel. Responde 1 (Regular), 2 (VIP), 3 (MEGAVIP) o 4 (ULTRAVIP).');
      return;
    }

    await pool.query(`
      UPDATE botcooldown_devices SET kpass_tier = $1
      WHERE discord_user_id = $2
    `, [tier, userId]);

    registrationSessions.delete(userId);
    const labels = { regular: 'Regular', vip: 'VIP', megavip: 'MEGAVIP', ultravip: 'ULTRAVIP' };
    await message.reply(
      '✅ Tu K-PASS quedó configurado como **' + labels[tier] + '**.\n' +
      'Los próximos temporizadores usarán los enfriamientos correspondientes. Los que ya están activos no cambiarán.'
    );
    return;
  }

  if (!session || session.step !== 'code') return;

  const normalizedCode = content.toUpperCase().replace(/\s/g, '');

  if (!/^[A-HJ-NP-Z2-9]{4}-?[A-HJ-NP-Z2-9]{4}$/.test(normalizedCode)) {
    await message.reply(
      'Ese código no tiene el formato correcto. Envíame el código que aparece en BotCooldown o escribe `cancelar`.'
    );
    return;
  }

  const code = normalizedCode.replace('-', '');
  const token = generateToken();
  const client = await pool.connect();

  try {
    await client.query('BEGIN');

    const result = await client.query(`
      SELECT installation_id
      FROM botcooldown_devices
      WHERE link_code_hash = $1
        AND code_expires_at > $2
      FOR UPDATE
    `, [hash(code), Date.now()]);

    if (result.rowCount === 0) {
      await client.query('ROLLBACK');
      await message.reply(
        '❌ El código no existe, ya fue utilizado o ha caducado. Solicita uno nuevo en BotCooldown.'
      );
      return;
    }

    const installationId = result.rows[0].installation_id;

    const existing = await client.query(`
      SELECT installation_id
      FROM botcooldown_devices
      WHERE discord_user_id = $1
        AND installation_id <> $2
    `, [userId, installationId]);

    if (existing.rowCount > 0) {
      await client.query('ROLLBACK');
      await message.reply(
        '⚠️ Esta cuenta de Discord ya está vinculada a otra instalación. Desvincúlala primero.'
      );
      return;
    }

    await client.query(`
      UPDATE botcooldown_devices
      SET discord_user_id = $1,
          api_token = $2,
          link_code_hash = NULL,
          code_expires_at = NULL,
          linked_at = NOW()
      WHERE installation_id = $3
    `, [userId, token, installationId]);

    await client.query('COMMIT');

    registrationSessions.set(userId, { step: 'tier', startedAt: Date.now() });

    await message.reply(
      '✅ **¡Cuenta vinculada correctamente!**\n\n' +
      'BotCooldown ya puede recibir tus temporizadores.\n\n' +
      'Ahora selecciona tu K-PASS actual:\n' +
      '1️⃣ Regular (sin VIP)\n2️⃣ VIP\n3️⃣ MEGAVIP\n4️⃣ ULTRAVIP\n\n' +
      'Responde con el número o el nombre del nivel. Si no eliges uno, se mantendrá Regular por defecto.'
    );

  } catch (error) {
    await client.query('ROLLBACK');
    console.error('Error al vincular Discord:', error);
    await message.reply('Ocurrió un error al vincular la cuenta. Inténtalo nuevamente.');
  } finally {
    client.release();
  }
}

client.on('messageCreate', async (message) => {
  try {
    if (message.author.bot) {
      const text = getMessageText(message);

      const rejected =
        text.includes('you already claimed') ||
        text.includes('you already received') ||
        text.includes('you have already received') ||
        text.includes('you have already placed') ||
        text.includes('you recently received') ||
        text.includes("you've already claimed") ||
        (text.includes('please wait') &&
          text.includes('before using more commands'));

      if (rejected) {
        const index = findRejectedAttemptIndex(
          pendingAttempts,
          message,
          Date.now(),
          rejectionWindowMs
        );

        if (index !== -1) {
          const [attempt] = pendingAttempts.splice(index, 1);
          clearTimeout(attempt.timeout);

          console.log(
            `Cooldown rechazado para ${attempt.command} de Discord ${attempt.discordUserId}. No se registra.`
          );
        } else {
          console.warn(
            `Respuesta de rechazo ambigua en canal ${message.channelId}; no se cancela ningún intento para evitar afectar a otro usuario.`
          );
        }
      }

      return;
    }

    if (message.channel.isDMBased()) {
      await handleDirectMessage(message);
      return;
    }

    const command = message.content.trim().toLowerCase();

    // Comando secreto de prueba, exclusivo del creador.
    if (command.startsWith('b!testcooldown')) {
      if (message.author.id !== OWNER_DISCORD_ID) {
        return;
      }

      const match = command.match(/^b!testcooldown\s+(\d+)$/);

      if (!match) {
        await message.reply('Uso: `b!testcooldown <minutos>` (1-60).');
        return;
      }

      const minutes = Number(match[1]);

      if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 60) {
        await message.reply('La duración debe estar entre 1 y 60 minutos.');
        return;
      }

      const linked = await pool.query(`
        SELECT discord_user_id
        FROM botcooldown_devices
        WHERE discord_user_id = $1
      `, [OWNER_DISCORD_ID]);

      if (linked.rowCount === 0) {
        await message.reply(
          'Primero debes vincular tu cuenta de Discord con BotCooldown.'
        );
        return;
      }

      const duration = minutes * 60;
      const startedAt = Date.now();

      await pool.query(`
        INSERT INTO botcooldown_events
          (discord_user_id, command, duration, started_at, source_message_id)
        VALUES ($1, $2, $3, $4, $5)
        ON CONFLICT (source_message_id)
          WHERE source_message_id IS NOT NULL
        DO NOTHING
      `, [
        OWNER_DISCORD_ID,
        'b!testcooldown',
        duration,
        startedAt,
        message.id
      ]);

      await message.reply(
        `✅ Cooldown de prueba creado: **${minutes} minuto(s)**.`
      );

      console.log(
        `Cooldown de prueba de ${minutes} minuto(s) creado para ${OWNER_DISCORD_ID}.`
      );

      return;
    }

    if (!cooldowns[command]) return;
    const linked = await pool.query(`
      SELECT discord_user_id, kpass_tier
      FROM botcooldown_devices
      WHERE discord_user_id = $1
    `, [message.author.id]);

    if (linked.rowCount === 0) return;

    const tier = linked.rows[0].kpass_tier || 'regular';
    const duration = getCooldownDuration(command, tier);
    if (!duration) return;

    const attempt = {
      command,
      duration,
      startedAt: Date.now(),
      channelId: message.channelId,
      messageId: message.id,
      discordUserId: message.author.id,
      timeout: null
    };

    pendingAttempts.push(attempt);

    attempt.timeout = setTimeout(async () => {
      const index = pendingAttempts.indexOf(attempt);
      if (index === -1) return;

      pendingAttempts.splice(index, 1);

      try {
        await pool.query(`
          INSERT INTO botcooldown_events
            (discord_user_id, command, duration, started_at, source_message_id)
          VALUES ($1, $2, $3, $4, $5)
          ON CONFLICT (source_message_id)
            WHERE source_message_id IS NOT NULL
          DO NOTHING
        `, [
          attempt.discordUserId,
          attempt.command,
          attempt.duration,
          attempt.startedAt,
          attempt.messageId
        ]);

        console.log(
          `Registrado ${attempt.command} para Discord ${attempt.discordUserId}.`
        );
      } catch (error) {
        console.error('Error guardando evento:', error);
      }
    }, rejectionWindowMs);

  } catch (error) {
    console.error('Error procesando mensaje:', error);
  }
});

// Solicitar un código de vinculación desde Android.
app.post('/link/request', async (req, res) => {
  try {
    const { installationId, installationSecret } = req.body;

    if (!validInstallationId(installationId) ||
        typeof installationSecret !== 'string' ||
        installationSecret.length < 32) {
      return res.status(400).json({ error: 'Datos de instalación inválidos.' });
    }

    const installationSecretHash = hash(installationSecret);
    const existing = await pool.query(`
      SELECT installation_secret_hash, discord_user_id
      FROM botcooldown_devices
      WHERE installation_id = $1
    `, [installationId]);

    if (existing.rowCount > 0) {
      if (existing.rows[0].discord_user_id) {
        return res.status(409).json({
          error: 'Esta instalación ya está vinculada. Desvincúlala primero.'
        });
      }

      if (existing.rows[0].installation_secret_hash !== installationSecretHash) {
        return res.status(403).json({
          error: 'El secreto de esta instalación no coincide. No se puede reclamar esta instalación.'
        });
      }
    }

    const code = generateCode();
    const codeHash = hash(code.replace('-', ''));
    const expiresAt = Date.now() + linkCodeLifetimeMs;

    const registered = await pool.query(`
      INSERT INTO botcooldown_devices
        (installation_id, installation_secret_hash, link_code_hash, code_expires_at)
      VALUES ($1, $2, $3, $4)
      ON CONFLICT (installation_id)
      DO UPDATE SET
        link_code_hash = EXCLUDED.link_code_hash,
        code_expires_at = EXCLUDED.code_expires_at
      WHERE botcooldown_devices.installation_secret_hash = EXCLUDED.installation_secret_hash
        AND botcooldown_devices.discord_user_id IS NULL
      RETURNING installation_id
    `, [installationId, installationSecretHash, codeHash, expiresAt]);

    if (registered.rowCount === 0) {
      return res.status(409).json({
        error: 'La instalación cambió o fue vinculada durante la solicitud. Vuelve a intentarlo.'
      });
    }

    res.json({
      code,
      expiresAt
    });

  } catch (error) {
    console.error('Error generando código:', error);
    res.status(500).json({ error: 'No se pudo generar el código.' });
  }
});

// Android consulta si Discord ya confirmó la vinculación.
app.post('/link/status', async (req, res) => {
  try {
    const { installationId, installationSecret } = req.body;

    if (!validInstallationId(installationId) ||
        typeof installationSecret !== 'string') {
      return res.status(400).json({ error: 'Datos inválidos.' });
    }

    const result = await pool.query(`
      SELECT discord_user_id, api_token, code_expires_at
      FROM botcooldown_devices
      WHERE installation_id = $1
        AND installation_secret_hash = $2
    `, [installationId, hash(installationSecret)]);

    if (result.rowCount === 0) {
      return res.status(403).json({ error: 'Instalación no autorizada.' });
    }

    const device = result.rows[0];

    res.json({
      linked: !!device.discord_user_id,
      token: device.api_token || null,
      codeExpiresAt: device.code_expires_at
    });

  } catch (error) {
    console.error('Error consultando vinculación:', error);
    res.status(500).json({ error: 'No se pudo consultar la vinculación.' });
  }
});

// Desvincular una instalación desde Android.
app.post('/link/unlink', async (req, res) => {
  try {
    const token = req.headers.authorization?.replace(/^Bearer\s+/i, '');

    if (!token) {
      return res.status(401).json({ error: 'Falta autorización.' });
    }

    const result = await pool.query(`
      UPDATE botcooldown_devices
      SET discord_user_id = NULL,
          api_token = NULL,
          link_code_hash = NULL,
          code_expires_at = NULL,
          linked_at = NULL
      WHERE api_token = $1
    `, [token]);

    if (result.rowCount === 0) {
      return res.status(401).json({ error: 'Token inválido.' });
    }

    res.json({ success: true });

  } catch (error) {
    console.error('Error desvinculando:', error);
    res.status(500).json({ error: 'No se pudo desvincular.' });
  }
});

async function authenticate(req, res, next) {
  const token = req.headers.authorization?.replace(/^Bearer\s+/i, '');

  if (!token) {
    return res.status(401).json({ error: 'Falta autorización.' });
  }

  try {
    const result = await pool.query(`
      SELECT discord_user_id
      FROM botcooldown_devices
      WHERE api_token = $1
        AND discord_user_id IS NOT NULL
    `, [token]);

    if (result.rowCount === 0) {
      return res.status(401).json({ error: 'Token inválido o desvinculado.' });
    }

    req.discordUserId = result.rows[0].discord_user_id;
    next();

  } catch (error) {
    console.error('Error autenticando:', error);
    res.status(500).json({ error: 'Error de autenticación.' });
  }
}

// Obtener exclusivamente los eventos de la cuenta vinculada.
app.get('/events', authenticate, async (req, res) => {
  try {
    const result = await pool.query(`
      SELECT
        id,
        command,
        duration,
        started_at AS "startedAt"
      FROM botcooldown_events
      WHERE discord_user_id = $1
      ORDER BY started_at DESC
      LIMIT 100
    `, [req.discordUserId]);

    res.json(result.rows);

  } catch (error) {
    console.error('Error consultando eventos:', error);
    res.status(500).json({ error: 'No se pudieron consultar los eventos.' });
  }
});
// Eliminar un evento sincronizado sin que vuelva a aparecer.
app.delete('/events/:id', authenticate, async (req, res) => {
  try {
    const result = await pool.query(`
      DELETE FROM botcooldown_events
      WHERE id = $1
        AND discord_user_id = $2
    `, [req.params.id, req.discordUserId]);

    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Evento no encontrado.' });
    }

    res.json({ success: true });

  } catch (error) {
    console.error('Error eliminando:', error);
    res.status(500).json({ error: 'No se pudo eliminar el evento.' });
  }
});

app.get('/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.status(200).json({ ok: true, database: 'connected' });
  } catch (error) {
    console.error('Comprobación de salud fallida:', error);
    res.status(503).json({ ok: false, database: 'disconnected' });
  }
});

app.get('/', (req, res) => {
  res.send('BotCooldown multiusuario está funcionando.');
});

const port = process.env.PORT || 3000;

async function startServer() {
  try {
    await initializeDatabase();

    app.listen(port, () => {
      console.log(`Servidor web iniciado en puerto ${port}`);
    });

    await client.login(process.env.DISCORD_TOKEN);

  } catch (error) {
    console.error('No se pudo iniciar BotCooldown:', error);
    process.exit(1);
  }
}

client.once('ready', () => {
  console.log(`Bot conectado como ${client.user.tag}`);
});

startServer();