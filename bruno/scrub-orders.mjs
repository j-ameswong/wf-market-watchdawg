// Replaces trader identity in a captured /v2/orders/* response, so the capture can be committed as
// a test fixture (SPEC §9: no trader profiles). Every order field is kept exactly as captured. Each
// owner becomes a numbered pseudonym, consistent within one file, with no avatar, zero reputation
// and a blank activity. Platform, crossplay, status, locale and lastSeen are kept.
//
// Writes one order per line, so a refreshed capture diffs readably:
//
//   node bruno/scrub-orders.mjs < response.json > market/src/test/resources/fixtures/v2-orders/<name>.json
//
// For a JSON array of socket envelopes from CaptureNewOrders.java, use --socket. This keeps
// every envelope and scrubs the user of each newOrder payload with the same pseudonym map.
import { readFileSync } from 'node:fs';

const args = process.argv.slice(2);
if (args.length > 1 || (args.length === 1 && args[0] !== '--socket')) {
  throw new Error('Usage: node bruno/scrub-orders.mjs [--socket] < capture.json');
}
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

const scrubOrder = (order) => (order.user ? { ...order, user: scrub(order.user) } : order);
if (args[0] === '--socket') {
  const frames = envelope.map((frame) =>
    frame.route === '@wfm|event/subscriptions/newOrder' ? { ...frame, payload: scrubOrder(frame.payload) } : frame,
  );
  process.stdout.write(`[\n${frames.map((frame) => JSON.stringify(frame)).join(',\n')}\n]\n`);
} else {
  const orders = envelope.data.map(scrubOrder);
  const head = JSON.stringify({ apiVersion: envelope.apiVersion, error: envelope.error });
  process.stdout.write(`${head.slice(0, -1)},"data":[\n${orders.map((o) => JSON.stringify(o)).join(',\n')}\n]}\n`);
}
