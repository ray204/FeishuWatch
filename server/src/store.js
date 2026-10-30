import { randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, renameSync, chmodSync } from 'node:fs';
import { dirname } from 'node:path';

// One process owns this store. Sync atomic writes prevent interleaved refresh commits.
export class Store {
  constructor(path, key) {
    if (!/^[a-f0-9]{64}$/i.test(key)) throw new Error('STORE_KEY must contain 64 hex characters');
    this.path = path;
    this.key = Buffer.from(key, 'hex');
    this.data = { pairs: {}, states: {}, devices: {} };
    try {
      const blob = JSON.parse(readFileSync(path, 'utf8'));
      const decipher = createDecipheriv('aes-256-gcm', this.key, Buffer.from(blob.iv, 'base64'));
      decipher.setAuthTag(Buffer.from(blob.tag, 'base64'));
      this.data = JSON.parse(Buffer.concat([decipher.update(Buffer.from(blob.body, 'base64')), decipher.final()]).toString());
    } catch (error) {
      if (error.code !== 'ENOENT') throw new Error('Cannot decrypt state; check STORE_KEY and data file');
    }
  }
  save() {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key, iv);
    const body = Buffer.concat([cipher.update(JSON.stringify(this.data)), cipher.final()]);
    mkdirSync(dirname(this.path), { recursive: true, mode: 0o700 });
    const tmp = `${this.path}.tmp`;
    writeFileSync(tmp, JSON.stringify({ iv: iv.toString('base64'), tag: cipher.getAuthTag().toString('base64'), body: body.toString('base64') }), { mode: 0o600 });
    chmodSync(tmp, 0o600);
    renameSync(tmp, this.path);
  }
}
