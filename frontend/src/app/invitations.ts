const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
export function invitationToken(input: string): string {
  const value = input.trim();
  if (tokenPattern.test(value)) return value;
  try {
    const url = new URL(value);
    const token = new URLSearchParams(url.hash.split('?')[1] || url.search).get('invite') || '';
    if (tokenPattern.test(token)) return token;
  } catch {}
  throw Error('This invitation link is incomplete. Ask the trip owner for a new link.');
}
export function invitationLink(
  token: string,
  location: { origin: string; pathname: string },
): string {
  return `${location.origin}${location.pathname}#/trips?invite=${invitationToken(token)}`;
}
