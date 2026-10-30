import { createServer } from 'node:http';
import { Store } from './store.js';
import { FeishuClient } from './feishu.js';
import { createApp } from './app.js';

const required = ['PUBLIC_URL', 'FEISHU_APP_ID', 'FEISHU_APP_SECRET', 'STORE_KEY'];
for (const name of required) if (!process.env[name]) throw new Error(`Missing ${name}`);
const publicUrl = process.env.PUBLIC_URL.replace(/\/$/, '');
const origin = new URL(publicUrl);
if (origin.origin !== publicUrl || origin.protocol !== 'https:') throw new Error('PUBLIC_URL must be a bare HTTPS origin');
const store = new Store(process.env.DATA_FILE || './data/state.enc', process.env.STORE_KEY);
const client = new FeishuClient({ appId: process.env.FEISHU_APP_ID, appSecret: process.env.FEISHU_APP_SECRET, redirectUri: `${publicUrl}/oauth/callback` });
const server = createServer({ maxHeaderSize: 8192, requestTimeout: 20000, headersTimeout: 15000 }, createApp({ store, client, publicUrl }));
server.listen(Number(process.env.PORT || 8787), process.env.HOST || '127.0.0.1', () => console.log('FeishuWatch bridge ready'));
