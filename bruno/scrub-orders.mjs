// Replaces trader identity in a captured /v2/orders/* response, so the capture can be committed as
// a test fixture (SPEC §9: no trader profiles). Every order field is kept exactly as captured. Each
// owner becomes a numbered pseudonym, consistent within one file, with no avatar, zero reputation
// and a blank activity. Platform, crossplay, status, locale and lastSeen are kept.
//
// Writes one order per line, so a refreshed capture diffs readably:
//
//   node bruno/scrub-orders.mjs < response.json > market/src/test/resources/fixtures/v2-orders/<name>.json
import { readFileSync } from 'node:fs';

const envelope = JSON.parse(readFileSync(0, 'utf8'));
const pseudonyms = new Map();

const scrub = ({ avatar, ...user }) => {
  if (!pseudonyms.has(user.id)) pseudonyms.set(user.id, String(pseudonyms.size + 1).padStart(4, '0'));
  const n = pseudonyms.get(user.id);
  return {
    ...user,
    id: `user${n}`,
    ingameName: `Trader${n}`,
    slug: `trader${n}`,
    reputation: 0,
    activity: { type: 'UNKNOWN', details: 'unknown' },
  };
};

const orders = envelope.data.map((order) => (order.user ? { ...order, user: scrub(order.user) } : order));
const head = JSON.stringify({ apiVersion: envelope.apiVersion, error: envelope.error });
process.stdout.write(`${head.slice(0, -1)},"data":[\n${orders.map((o) => JSON.stringify(o)).join(',\n')}\n]}\n`);
