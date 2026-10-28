
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
  'K!drop': 3600,
  'K!megadrop': 7200,
  'K!luckycard': 3600,
  'K!album drop': 3600,
  'K!album megadrop': 7200,
  'K!stamp drop': 3600,
  'K!rewards': 86400,
  'K!mine': 3600,
  'K!fish': 3600,
  'K!work': 3600
};

const recentEvents = [];

client.on('messageCreate', (message) => {
  if (message.author.bot) return;

  const command = message.content.trim().toLowerCase();
  const duration = cooldowns[command];

  if (!duration) return;

  const event = {
    command,
    duration,
    startedAt: Date.now()
  };

  recentEvents.unshift(event);
  if (recentEvents.length > 50) recentEvents.pop();

  console.log(`Detectado ${command} de ${message.author.username}`);
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
