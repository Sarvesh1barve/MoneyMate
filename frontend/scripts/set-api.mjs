import { readFile, writeFile } from 'node:fs/promises';
const input = process.argv[2] ?? process.env.MONEYMATE_API_URL ?? '';
if (input) {
  const u = new URL(input);
  if (
    (u.protocol !== 'https:' &&
      !(u.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(u.hostname))) ||
    u.pathname !== '/' ||
    u.search ||
    u.hash ||
    u.username ||
    u.password
  )
    throw Error('Expected an HTTPS origin (or localhost HTTP), without path or credentials.');
}
const config = JSON.parse(await readFile('public/config.json', 'utf8'));
config.apiUrl = input ? new URL(input).origin : '';
await writeFile('public/config.json', JSON.stringify(config, null, 2) + '\n');
console.log(
  input ? 'Public API origin configured.' : 'API origin left blank; users can set it in the app.',
);
