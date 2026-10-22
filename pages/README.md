# pages

grog's page on GitHub Pages, at https://ericdallo.github.io/grog/. For now,
only its OAuth relay (plans/integrations.md, "The relay page"):

- `oauth/client.json`: grog's client ID metadata document. Authorization
  servers that take those (MCP's preferred way) know grog by this URL, its
  `client_id`, without anyone registering it. grog is a public client: no
  secret, PKCE always.
- `oauth/callback/`: where authorization servers send the browser back,
  with a code. The state says where grog is on the home network
  (`<address>~<random>`); the page checks the address is private and sends
  the browser on to `http://<address>/c/callback`, the query untouched.
  When the browser won't go there, the user copies the page's address and
  pastes it into grog's page. The logic is in `relay.js`, pure and tested.
- `oauth/index.html`: what this is.

It never sees tokens: codes pass through it, useless without the verifier
grog keeps.

## Publishing

`.github/workflows/pages.yml` runs the tests (`node --test` here) and
publishes this directory on every push to master that touches it (or by
hand, from the Actions tab). The repo's Pages source must be "GitHub
Actions" (Settings, Pages).

## Forks

A fork publishes its own at `https://<owner>.github.io/<repo>/`: change the
URLs in `oauth/client.json` (`client_id`, `redirect_uris`, `client_uri`) to
it, and set grog's `integrations.relay` setting to
`https://<owner>.github.io/<repo>/oauth/`.

## Tests

    cd pages && node --test
