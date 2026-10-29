
const { Client, GatewayIntentBits } = require('discord.js');
const express = require('express');

const app = express();
app.use(express.json());

const client = new Client({
  intents: [
    GatewayIntentBits.Guilds,
    GatewayIntentBits.GuildMessages,
    GatewayIntentBits.MessageContent
  ]
});

const cooldowns = {
  'k!drop': 3600,
  'k!d': 3600,

  'k!daily': 86400,
  'k!weekly': 604800,

  'k!megadrop': 7200,
  'k!md': 7200,

  'k!lucky card': 3600,
  'k!l': 3600,

  'k!album drop': 3600,
  'k!ad': 3600,

  'k!album megadrop': 7200,
  'k!amd': 7200,

  'k!stamp drop': 3600,
  'k!sd': 3600,

  'k!mine': 3600,
  'k!m': 3600,

  'k!fish': 3600,
  'k!f': 3600,

  'k!work': 3600,
  'k!w': 3600
};

const recentEvents = [];


const pendingAttempts = [];
const rejectionWindowMs = 15000;

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

client.on('messageCreate', (message) => {
  const text = getMessageText(message);

  if (message.author.bot) {
    const rejected =
  text.includes('you already claimed') ||
  text.includes('you already received') ||
  text.includes('you have already received') ||
  text.includes('you have already placed') ||
  text.includes('you recently received') ||
  text.includes("you've already claimed");

    if (rejected) {
      const index = pendingAttempts.findLastIndex(
        attempt =>
          attempt.channelId === message.channelId &&
          Date.now() - attempt.startedAt < rejectionWindowMs
      );

      if (index !== -1) {
        const [attempt] = pendingAttempts.splice(index, 1);
        clearTimeout(attempt.timeout);

        console.log(
          `Cooldown rechazado para ${attempt.command}. No se registra el uso.`
        );
      } else {
        console.log(
          'KBOT indicó cooldown, pero no se encontró un comando pendiente.'
        );
      }
    }

    return;
  }

  const command = message.content.trim().toLowerCase();
  const duration = cooldowns[command];

  if (!duration) return;

  const attempt = {
    command,
    duration,
    startedAt: Date.now(),
    channelId: message.channelId,
    timeout: null
  };

  pendingAttempts.push(attempt);

  console.log(
    `Comando ${command} pendiente de comprobar la respuesta de KBOT.`
  );

  attempt.timeout = setTimeout(() => {
    const index = pendingAttempts.indexOf(attempt);

    if (index === -1) return;

    pendingAttempts.splice(index, 1);

    recentEvents.unshift({
      command: attempt.command,
      duration: attempt.duration,
      startedAt: Date.now()
    });

    if (recentEvents.length > 50) recentEvents.pop();

    console.log(
      `Registrado ${attempt.command}: no se detectó respuesta de cooldown.`
    );
  }, rejectionWindowMs);
});


app.get('/events', (req, res) => {
  res.json(recentEvents);
});

app.get('/', (req, res) => {
  res.send('Cooldown Detector está funcionando.');
});

const port = process.env.PORT || 3000;

app.listen(port, () => {
  console.log(`Servidor web iniciado en puerto ${port}`);
});

client.once('ready', () => {
  console.log(`Bot conectado como ${client.user.tag}`);
});

client.login(process.env.DISCORD_TOKEN);
