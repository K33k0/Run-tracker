import { createBroker } from './broker.mjs';
const clients = Object.fromEntries(['strava', 'smashrun', 'runalyze'].map(provider => [provider, { id: process.env[`${provider.toUpperCase()}_CLIENT_ID`], secret: process.env[`${provider.toUpperCase()}_CLIENT_SECRET`] }]));
const server = createBroker({ baseUrl: process.env.PUBLIC_URL || 'http://localhost:8787', clients });
server.listen(Number(process.env.PORT || 8787), process.env.HOST || '127.0.0.1', () => console.log('RunZone account service started'));
