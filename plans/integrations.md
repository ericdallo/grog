# grog's integrations: the design

Context for whoever builds what grog connects to besides its own machine:
music services, the user's computers, home lights, other platforms. How
they plug in, how someone connects one, what was decided with the user in
October 2026 and why, and what's left to check. Keep it up to date as the
phases land.

## Where it stands

- Phase 1 is built (October 2026): one way of connecting things, with the
  ChatGPT sign-in and computers on it, both working as before.
  - `grog.connect`, the engine: sessions (one at a time, 10 minutes, opened
    through the local API: the skin, the voice's `connect` tool, `grog
    connect`), the page on the network (`resources/grog/connect.html`,
    `/c/<token>`, its `state`, what it posts, `/c/callback`), and the steps
    `device-code`, `oauth` (back to loopback, or to grog's page on the
    network, or the address pasted on the page), `form`, `code-compare`,
    and `script` (a step showing a command).
  - `grog.integrations`, the catalog: `openai` (`grog.signin`, which
    replaced `grog.login`: ways `device` and `browser`, and the sign-in
    grog keeps up for its skin while it has nothing to pay with) and
    `computer` (`grog.pairing` on the engine, its protocol unchanged).
  - `grog.connections`, the vault: `integrations.json` and
    `secrets/<id>.json`; `auth.json` moves there when grog starts.
  - `grog.qr`: QR codes in Babashka (`GET /v1/qr`), the same as
    `qrencode`'s, which the package's tests check.
  - The API (`/v1/integrations`, `/v1/connect`, `/v1/connections`), the
    events (`connect`, and `integrations` in the status), the CLI (`grog
    integrations`, `connect`, `disconnect`; `login` and `logout` stay), the
    skin's card and its Integrations, and the settings
    (`integrations.host`, `.port`, which `computers.pairing` became;
    `services.grog.integrations.openFirewall` on NixOS).
- Where it differs from what's below:
  - The API also has `GET /v1/connect` (the session, polled by the CLI)
    and `POST /v1/connect/allow` and `/deny` (a code-compare step's
    answer, from the card or the CLI).
  - Connections' ids: the integration's for those it has one of
    (`openai`), `<integration>:<name>` for the others (`computer:deluxe`).
  - How one is: `connected`, or `signed-out` once a refresh is refused (and
    the API key pays meanwhile, if there's one). Nothing checks computers
    yet: no `unreachable`.
  - The sign-in grog keeps up itself has no Cancel on its card. A
    computer's card shows what to run there, not a QR code: it's for the
    computer itself.
  - The page offers an integration's other ways (signing in in a browser
    rather than with a code), on the same link.
  - Nothing uses the `form` step yet, nor `/c/callback`: they're tested
    with made-up integrations. (Phase 2 uses both.)
- Phase 2 is built (October 2026): MCP servers as integrations.
  - `grog.mcp`, the client: tools (listing every page, calling, the list
    changing) and elicitation, over stdio or Streamable HTTP.
  - `grog.catalog`, the entries as `server.json` (shipped in
    `resources/grog/integrations/`, added ones in `<state>/catalog/`),
    and `grog.registry`: the official MCP Registry's v0.1 API (search,
    a server's latest version), and entries made from a package spec
    (`npm:name@1.2.3`, `pypi:name==1.2.3`) or an address.
  - `grog.mcp-connect`, connecting one on the engine: a form for what it
    needs, then grog starts or reaches it and lists its tools; adding,
    removing, searching. `grog.mcp-auth`: protected resource metadata (RFC
    9728), authorization server metadata (RFC 8414, OpenID discovery), the
    client (one the user made, grog's client ID metadata document, dynamic
    registration), PKCE, the resource (RFC 8707), the issuer (RFC 9207),
    refreshing, revoking on disconnect. The engine's `oauth` step comes
    back through the relay page (`{:relay base}`).
  - `grog.servers`, the live servers: started on first use (a package
    through the runner, a remote with its token, refreshed), stopped after
    10 minutes without calls (checked every minute), their tool lists kept
    in `<state>/tools/<id>.json`.
  - `grog.mcp-tools`, the model's tools: `<prefix>__<tool>`, which are
    shown, the guards, the policy. `grog.tools` grew `:open-world?`:
    after such a tool, as after a web search, nothing changes in the turn.
    `grog.asks`, asking first; `grog.elicit`, what a server asks mid-call;
    and the engine's new steps for them, `confirm` and `link`, and typed
    form fields (number, integer, boolean, choice, choices, formats).
  - `grog.runner`, `grog mcp --socket`: `services.grog.mcp` on NixOS, on in
    grognix with `programs.nix-ld`.
  - The relay page, `pages/oauth/` (`client.json`, `callback/` with its
    logic in `relay.js`, tested with `node --test`), published by
    `.github/workflows/pages.yml`; the `integrations.relay` and
    `integrations.runner` settings.
  - The API (`POST /v1/integrations`, `DELETE /v1/integrations/<id>`,
    `GET /v1/integrations/search`, `GET /v1/connections/<id>/tools`,
    `PUT /v1/connections/<id>/tools/<name>`), the CLI (`grog integrations
    add`, `remove`, `search`; `grog tools`), and the skin (the card's
    `confirm` and `link`, Integrations saying which servers the user added,
    which are in the cloud, and how many tools each has; labels for
    `<server>__<tool>`).
- Where phase 2 differs from what's below:
  - MCP 2026-07-28 is stateless: no `initialize`, every request carries
    grog's revision, capabilities and client info in its `_meta`, grog
    asks `server/discover` first, tool calls over HTTP name the tool in
    headers (`Mcp-Name`, and `x-mcp-header` arguments), tool lists say how
    long they're good for (`ttlMs`), and elicitation comes back as an
    `input_required` result, answered by calling again with the answers.
    Servers that don't know it get the handshake of 2025-11-25 (down to
    2025-03-26), with elicitation as requests of their own; a stdio server
    not answering `server/discover` in 5 s is taken for one of those.
  - No catalog entries shipped in phase 2: Home Assistant came with phase
    3. grog's own tools stay Clojure tools, not servers inside it, and
    capability tools came with phase 3 (`lights`; no `music` yet).
  - grog's `_meta` fields were `id`, `prefix`, `icon`, `about`, `show`,
    `policy`, `cloud` and `added`: `connect` and `capabilities` came with
    phase 3. Without `connect`, what connecting asks comes from
    `server.json`: a package's
    environment variables and arguments' variables, a remote's headers and
    the variables in its address, in a form (`isSecret` warned about).
    Everything of grog's `_meta` in an entry the user added is dropped, and
    its annotations aren't trusted: its tools count as changing things and
    as open-world, though those marked read-only or non-destructive don't
    ask first.
  - A package is preferred to a remote when an entry has both and the
    runner is set. Packages only from npm's and PyPI's public registries,
    versions pinned; remotes over https, or http on the local network; no
    OCI images, MCPB bundles or SSE remotes. Added remotes outside the
    local network count as in the cloud.
  - Asking first works across turns: the call doesn't run, the model asks,
    and the same call (same arguments, same conversation, a later turn,
    within 5 minutes) runs. The card asks too, when it's free, for 2
    minutes: Allow says yes for the user, as a message in that
    conversation, which starts a turn; Deny forgets it.
  - Tools' modes and whether they're shown are kept in `tools.json`,
    changed with `grog tools` or the API: no screen for them yet. Shown by
    default: the entry's `show`, else the first 40.
  - Elicitation is answered on the card and the page (a form, or a link
    with its QR code and Done), only when the card is free, within 3
    minutes; not by voice yet.
  - Servers are added from the CLI or the API only, not from the screen.
    The registry's search is slow (often 20 s or more; grog waits 30 s).
  - A connection's `detail` says how many tools it has. One whose token
    can't be refreshed, or that answers 401, becomes `signed-out`.
  - The runner keeps a server only while grog's connection to it is open;
    grog closes it after 10 minutes without calls. It logs what a server
    says on stderr until it first answers (npm's and uv's errors), never
    later. On NixOS, uv uses nixpkgs' Python (`UV_PYTHON`, downloads off).
  - `client.json` names only the relay's callback, no loopback address:
    signing in to MCP servers always goes through the relay page (or the
    address pasted on grog's page). Home Assistant, in phase 3, doesn't:
    its own sign-in comes straight back to grog's page.
  - A 403 asking for more scopes (`insufficient_scope`) isn't handled yet:
    the call fails (`grog.mcp-auth/step-up-scope` is there, unused).
- Phase 3 is built (October 2026): Home Assistant, and `lights`.
  - `resources/grog/integrations/home-assistant.json`, the first entry
    grog ships: its remotes (`{url}/api/mcp/assist`, then `{url}/api/mcp`),
    its ways (signing in, or a long-lived token), its name (`location_name`
    of `/api/config`), what to say on a 404 (no MCP server set up there) or
    a 403 (admins only), `lights` mapped to its tools, and its policy.
  - `grog.catalog` reads `connect` (`ways`), `name`, `errors` and
    `capabilities` in shipped entries, and fills in `{variables}`
    (`template`, `remote-urls`).
  - `grog.mcp-connect` runs an entry's ways: the steps `discover`, `form`
    (fields of format `uri` taken as http or https addresses) and `oauth`
    (client `lan`), then the way's headers; it reaches the first remote
    that answers and keeps it in the connection's secrets (`:way`, `:url`,
    `:headers`, the auth and the revocation endpoint), which
    `grog.servers` starts from.
  - `grog.mdns`: DNS-SD over mDNS (RFC 6763, 6762): `avahi-browse`, else a
    one-shot query of its own; what's found becomes an address from its
    TXT (`internal_url`, `base_url`) or its first IPv4 address and port.
  - `grog.mcp-auth`: resource metadata may name a resource above the
    server's own when it came from the root well-known address or the
    challenge's (RFC 9728), and nothing empty goes in the authorization or
    token requests (no `resource` without one).
  - `grog.capabilities`: `lights` (`on`, `off`, `set`, `state`), offered by
    `grog.tools` when something connected can do it.
  - `grog.mcp-tools`: `matches?` and `find-tool`, for tool names that
    change by version.
  - The module puts avahi on grog's path; the skin labels `lights` ("Seeing
    to the lights", a bulb).
  - A fake Home Assistant (`start-ha!` in `test_helpers.clj`), as its
    source has it, for the unit and VM tests.
- Where phase 3 differs from what's below:
  - `connect` is a list of ways, each `{id label steps headers}` (a bare
    list of steps is one way), the first the usual one; the page offers the
    others. Each step adds to the variables the next ones, the headers
    (`Bearer {token}`) and the remotes' addresses use. `oauth` comes last.
  - Two remotes: `/api/mcp/assist` first (newer Home Assistants), then
    `/api/mcp`, tried on a 404. grog only checks for them once signed in:
    a 404 from both says how to add the integration, as the entry's
    `errors` say, and a 403 that only admins may (`/api/mcp`'s setting).
  - Discovery asks `avahi-browse` first: on grognix the firewall would drop
    unicast replies to grog's own port. Without avahi, or when it finds
    nothing, grog sends a legacy unicast query (RFC 6762 §6.7) from an
    ephemeral port. Each takes 2 s at most. There's no list to pick from:
    the first found fills in the form's address, the others named in it.
  - Home Assistant's tool names now carry prefixes (`intent__HassTurnOn`),
    which change by version: a name in `show`, `policy` and
    `capabilities` also matches a server's ending in `__` and that name.
  - Home Assistant's sign-in: PKCE (S256), grog's address on the network
    as the client id (`http://<address>/`, port 8485 included), coming
    back to `/c/callback` there, no resource named. The tokens refresh
    (Home Assistant gives no new refresh token), and disconnecting revokes
    them at `/auth/revoke`. A pasted long-lived token is kept as a header,
    and stays valid after disconnecting.
  - Capabilities only come from shipped entries: `{action {tool args}}`,
    a `"{param}"` in the arguments being the call's (left out when it has
    none). `lights` goes through the first connected entry, in catalog
    order, mapping it whose cached tools include its `on` and `off`;
    `state` reads `GetLiveContext`.
  - `lights` is grog's own: it never asks, though it doesn't run after a
    web search, but for `state`. Home Assistant's own `HassTurnOn` and
    `HassTurnOff` act on any domain, locks included, so the entry doesn't
    allow them: they ask first by default, unless Home Assistant marks them
    non-destructive (its annotations count, as the entry is shipped).
    `GetLiveContext`, `HassLightSet` and `HassClimateSetTemperature` are
    allowed.
  - The entry has no `show`: the model sees the first 40 of Home
    Assistant's tools, next to `lights`.
  - No switches or climate capabilities yet.
  - It was tested against a fake Home Assistant (unit and VM tests), not
    a real one yet.
- Music is built (October 2026), YouTube Music first: the user chose it
  ahead of Spotify.
  - grog-music (`music/`, `services.grog.music`, on in grognix): a Python
    service in the session of the user with the speaker, its socket in
    `/run/grog-music` (the `grog-music` group). A queue of playable items
    (`{url title artist album}`: http(s), YouTube's through yt-dlp, or
    `file://`) played by mpv, with its volume and ducking; YouTube Music's
    search, playing (a song then its up next, an album, a playlist, an
    artist's top songs, the user's playlist named most like what was said,
    their likes) and library, as `youtube-music.*` commands. ytmusicapi
    pinned at 1.12.3 (nixpkgs' 1.12.0 breaks on YouTube Music's answers),
    yt-dlp with Deno and yt-dlp-ejs, both from nixpkgs.
  - `grog.player`, its client; `grog.music`, the `music` tool (`play`
    with a `query`, a `kind`, `song`, `album`, `playlist`, `artist`,
    `my-playlist` or `liked`, and `shuffle`; `pause`, `resume`, `stop`,
    `next`, `previous`, `volume`, `status`), a capability tool offered when
    something plays music, and its providers: YouTube Music, built in,
    and the connected entries mapping `music` in their `capabilities`, as
    `lights` is (`grog.capabilities/sources`, at least `play` mapped to a
    tool they have). What plays: `music.provider` (the settings tool's
    `music_provider`, checked to be there), else the first connected, else
    the first there.
  - `grog.youtube-music`, an integration of grog's own, offered with a
    player: anonymous search and play; connecting the user's Google account
    (a `device-code` step at Google, RFC 8628) adds their library. Two
    ways: the Google client grog was given in its service's environment
    (`GROG_GOOGLE_CLIENT_ID`, `GROG_GOOGLE_CLIENT_SECRET`: a "TVs and
    Limited Input devices" client; not shipped in the repository, as
    GitHub's and Google's scanners take its secret for a leak, though
    Google doesn't treat it as confidential; the way is only offered when
    it's there), or a client the user made, entered in a form first.
    Shipping one to every grog, so that nobody has to make one, stays open. The tokens refresh in grog, which hands
    them to grog-music with each request; disconnecting revokes them.
  - The API (`POST /v1/music`), the CLI (`grog music`), the skin (`music`'s
    label and a note icon), and the settings (`music.socket`,
    `music.provider`).
- Where music differs from what's below ("Music and targets", "YouTube
  Music", "The voice, with music on grog"):
  - Targets: only grog's default sink so far. A Bluetooth speaker paired
    with the machine works through it, as the default sink; nothing pairs
    one from the screen or by voice yet. No default target to choose, no
    Spotify (Connect, go-librespot, its MCP server), computers or Cast yet.
  - The music capability has two kinds of providers: grog's own, playing
    through grog-music, and catalog entries mapping it (none shipped yet).
    A provider can't say where it plays.
  - ytmusicapi's OAuth is refused by YouTube Music (HTTP 400,
    sigma67/ytmusicapi#813), so the user's library comes through the
    YouTube Data API v3: their playlists, a playlist's songs, and their
    likes (all of YouTube's, music or not), not their saved albums or
    history. grog-music tries ytmusicapi first, and after a refusal not
    again for 6 hours. Search and play are anonymous.
  - The Google client is grog's own, shipped by the project, or the user's
    own: not only the user's. Shipped, its consent screen must be
    published (in testing, refresh tokens expire after 7 days); unverified,
    it warns and takes 100 users; its Data API quota is shared by every
    grog.
  - Audio focus is the brain's, not grog-voice's: `grog.music` watches
    grog-voice's states in the events and ducks grog-music (to 20 of 100)
    while it hears someone after its name, is awake or asked, and while
    grog answers aloud; then back. grog-voice didn't change. Touching the
    face while grog answers stops it and pauses the music (the `stop`
    action); it doesn't stop it whatever happens.
  - Hearing its name over its own music, like Alexa, in three parts:
    - Echo cancellation with the music as the reference
      (`services.grog.music.echoCancel`, on by default): PipeWire's
      echo-cancel module (WebRTC's canceller, a high-pass filter, no noise
      suppression or gain control) in a PipeWire process of its own, a
      user service of grog's music user (`grog-echo-cancel`). grog-music
      plays into its sink `grog-music-out` (`audioDevice`), which goes on
      to the default speaker; grog-voice records its source `grog-mic`.
      grog's own voice and earcons bypass it: grog-voice stays half
      duplex. Both nodes rank below real devices, never the defaults;
      without the canceller, WirePlumber falls back to the defaults and
      moves the streams back once it's there. PipeWire 1.6's WebRTC
      (webrtc-audio-processing 2, AEC3) takes only `high_pass_filter`,
      `noise_suppression`, `gain_control` and `mobile_mode`: delay
      estimation and the extended filter are its own.
    - grog tells grog-voice whether its player plays (`music` in `GET
      /v1/voice/commands`): after each music action and the stop action,
      every 5 s while it plays, once a minute otherwise. grog-voice looks
      for its name in short windows then.
    - The music ducks once grog heard its name (as above).
  - Not measured on grognix yet: the canceller's CPU and how much of the
    music it takes out with the speaker beside the mic (it clipped grog's
    own voice there), transcription's CPU and false wakes with music
    playing.
  - grog-music gets `CPUWeight=50`: grog-voice, on the same CPU, comes
    first.
- Next: measuring music on grognix (CPU, false wakes, the duck level), and
  shipping grog's Google client; then Spotify (see "Phases").

## What it should feel like

- **One way for everything.** On grog's screen, "Integrations" in its
  actions: each one with how it is (connected as greg@..., not connected,
  needs signing in again), and Connect or Disconnect. By voice: "Grog,
  conecta o Spotify". On the CLI: `grog connect spotify`.
- **Connecting opens a window**, for a few minutes. grog's screen shows a
  short link and its QR code (`http://192.168.15.10:8485/c/K7Q2M9`). Any
  phone or computer at home opens it and follows the steps that integration
  takes: sign in to the service, enter a code on its site, press a button on
  a bridge, scan a code with the vendor's app, run a command. grog's screen
  follows along, and both say when it's connected. Then grog can use it.
- **What the user asks doesn't depend on what's behind.** "Toca Legião
  Urbana", "apaga a luz da sala", "como está o notebook?" work whichever
  service or hub is connected.
- What it should handle:
  - "Toca Legião Urbana": plays on the default target.
  - "A partir de agora toca na caixa da sala": the default target changes,
    and stays.
  - "Toca no celular": plays there, this once.
  - "Apaga a luz da sala": Home Assistant or the Wi-Fi bulbs, whichever is
    connected.
  - "Conecta o Home Assistant": grog finds it on the network, and the user
    signs in to it on their phone.

## Decisions

Made with the user in October 2026, with what was weighed.

1. **Everything is an MCP server.** grog is an MCP host: integrations are
   MCP servers, and grog's own tools become servers inside it, with the
   same shape. MCP is the standard for giving a model tools, servers exist
   for Home Assistant, Spotify and YouTube Music, and anyone can write one.
   grog keeps what MCP leaves to the host: which servers it knows, getting
   their credentials, running them, which tools the model sees, guards, and
   one way of connecting for all. Rejected: integrations only as Clojure
   namespaces (less to run, but nothing from outside plugs in).
2. **Connecting only in a window opened at grog** (its screen, by voice, or
   its CLI), like pairing a computer: the page on the network only exists
   for those minutes, behind a token in the link. Rejected: devices paired
   once that manage grog anytime (a remote skin: more to protect over plain
   HTTP), and an admin page always open on the network.
3. **A static page of the project for OAuth**, on GitHub Pages: it's grog's
   identity as an OAuth client (its client ID metadata document) and the
   page providers send the browser back to, which hands the code to the grog
   on the network. Providers only send browsers back to HTTPS or loopback
   addresses, never to `http://192.168.x.x`. It never sees tokens: a code is
   useless without PKCE's verifier, which stays on grog. When that last hop
   fails, the user pastes the address the browser ended at into grog's
   page. Rejected: nothing outside (pasting every time, or signing in on
   grog's own screen).
4. **Local servers installed at run time**, with `npx` and `uvx`, so that a
   new integration can be added from the screen, without touching Nix. They
   run in a service of their own, apart from grog (see "The runner"), with
   the versions grog knows pinned. Rejected: only servers packaged with
   grog in Nix (reproducible, but adding one takes a new grog), and only
   remote servers.
5. **Music plays on a default target**: grog itself (its speaker, or a
   Bluetooth speaker paired with it) unless the user sets another or asks
   ("a partir de agora toca na caixa da sala"). The user wants both grog
   playing and grog telling other devices to play.
6. **Home Assistant first** for the house, as it covers lights, plugs and
   sensors at once, if it's quick for the user: it is when they already run
   it. Then Wi-Fi bulbs (Tuya, Smart Life), the common ones in Brazil.

## The pieces

### Servers

Three kinds, one interface for the rest of grog:

- **Inside grog**, in Clojure: grog's tools today (`computer`, settings,
  memory, voice, the machine's tools through its hands, power, update),
  described as MCP tools (name, title, description, input schema,
  annotations). Only their shape changes: they stay Babashka code, tested as
  they are.
- **Local**, over stdio: npm or PyPI packages, started by the runner.
- **Remote**, over Streamable HTTP: Home Assistant on the network, services
  on the internet.

What grog uses of MCP: tools (listing, calling, the notification that the
list changed), elicitation (a server asking the user something in the
middle of a call, as a form or as a link to open), and authorization for
remote servers. Not sampling: servers don't get to use grog's model. It
speaks the current revision (2026-07-28 at the time of writing), and the
older ones servers answer with.

### The catalog

The integrations grog knows, and those the user added.

- **Format**: the official MCP Registry's `server.json` (`packages` with
  their registry, identifier, version and `runtimeHint`, `npx` or `uvx`;
  `remotes` for HTTP servers; `environmentVariables`, `isSecret` for
  secrets), with grog's own fields under `_meta`, in a namespace of its own
  (`io.github.ericdallo.grog`): how to connect it (its steps), which
  capabilities it covers and how, which of its tools the model sees,
  default policy, icon, a line for the screen.
- **Shipped with grog** in `resources/grog/integrations/`, versions pinned,
  updated with grog.
- **Added by the user**: found in the official registry
  (`registry.modelcontextprotocol.io`) or given as a package or a URL. Its
  entry comes from its `server.json`: what `isSecret` marks goes to the
  vault, the rest to a form. Kept in the state directory. The screen says
  plainly that grog didn't check it.

A sketch of grog's own entry for Home Assistant (`{url}` comes from its
steps):

```json
{
  "name": "io.github.ericdallo.grog/home-assistant",
  "title": "Home Assistant",
  "remotes": [{"type": "streamable-http", "url": "{url}/api/mcp"}],
  "_meta": {
    "io.github.ericdallo.grog": {
      "icon": "home",
      "connect": [
        {"step": "discover", "service": "_home-assistant._tcp", "field": "url"},
        {"step": "form", "fields": [{"id": "url", "label": "Its address",
                                     "example": "http://homeassistant.local:8123"}]},
        {"step": "oauth", "authorize": "{url}/auth/authorize", "token": "{url}/auth/token",
         "client": "lan"}
      ],
      "capabilities": {"lights": {"on": "HassTurnOn", "off": "HassTurnOff", "set": "HassLightSet"}},
      "show": ["HassTurnOn", "HassTurnOff", "HassLightSet", "GetLiveContext"]
    }
  }
}
```

### Connections

- A connection is one thing connected: an account, a bridge, a computer.
  An integration may have several (computers already do; two Spotify
  accounts).
- `integrations.json` in the state directory: the connections, by id, with
  their integration, a name for the screen (the account, the computer),
  when they were added and how they are. Their secrets (tokens, refresh
  tokens, client ids the user made, keys) go in `secrets/<id>.json`, 0600,
  through `state/write!`: never in the settings, Nix, logs, events or the
  model's context.
- How one is: connected, needs signing in again (a refresh failed),
  unreachable, failing. Checked when used, and now and then.
- Disconnecting revokes what the service lets revoke (an OAuth revocation
  endpoint; a computer stops letting grog in), forgets the secrets, and
  stops its server.

### Connecting

One engine for every integration, made of `grog.login` (a state machine
for sign-ins: codes, browser sign-ins with PKCE, kept alive, watched,
shown without secrets) and `grog.pairing` (a time-limited listener on the
network, generation-guarded timers, Allow and Deny at grog).

- **A session** is opened by someone at grog: the skin, the voice's
  `connect` tool, the CLI (the local API only). One at a time, for 10
  minutes (signing in to a service can take a while). Its view, without
  secrets, goes to whoever watches grog (a `connect` slice in
  `/v1/events`), which the skin draws as the card.
- **The page on the network** is served by pairing's listener (port 8485,
  open in the firewall), only while a session or a pairing is open:
  - `GET /c/<token>`: the page. The token (10 random characters or more) is
    only on grog's screen and CLI, in the link and the QR code; without it,
    nothing answers.
  - `GET /c/<token>/state`: what the page shows now (polled).
  - `POST /c/<token>/<step>`: what the user enters (JSON only, so pages
    from elsewhere can't post, as today).
  - `GET /c/callback`: where OAuth comes back to, through the relay page or
    straight from an authorization server on the network (Home Assistant).
  - Pairing's routes stay, for computers.
  - The page is plain HTML, a Selmer template like `pairing.html`, with a
    little JS to follow the steps, and no dependencies.
- **Steps**: an integration's way of connecting is a list of them. Each
  says what the page shows and what grog's card shows:
  - `discover`: devices found on the network over mDNS (a Home Assistant,
    a Hue bridge), to pick one rather than typing an address.
  - `form`: fields to fill (an address, a client id, a token, a choice).
    A secret typed here crosses the network in plain HTTP: the page says
    so, and other steps come first when the service has them.
  - `oauth`: authorization code with PKCE (S256), a state bound to the
    session, the issuer checked (RFC 9207), and the resource named (RFC
    8707) for MCP servers. The client is grog's client ID metadata document
    on the project's page, or one the user made (Spotify, Google; a guided
    form step first), or, for Home Assistant, grog's own address on the
    network. It comes back through the relay page, or straight to grog's
    page when the authorization server is on the network, or to loopback
    when signing in on grog's own screen.
  - `device-code`: a code to enter on the provider's site (RFC 8628). The
    ChatGPT sign-in today; Google (YouTube, through a "TVs and Limited Input
    devices" client), Microsoft and GitHub have it.
  - `app-scan`: the vendor's app scans a QR code, then grog waits for the
    confirmation (Tuya and Smart Life). The code shows on grog's screen: the
    phone the page is open on can't scan its own.
  - `press`: a button pressed on a device, then grog asks it for a key
    (Philips Hue's bridge).
  - `zeroconf`: the service's own app hands grog its credentials (Spotify
    Connect: picking grog as a device in the Spotify app).
  - `code-compare`: the same code on both screens, Allow at grog
    (computers).
  - `script`: a command to run there (computers' `curl ... | bash`).
- **Elicitation**: a server asking, mid-call, for a choice or a short
  answer, gets it by voice (grog asks, the next thing said answers), or as
  a form on the card. A server asking the user to open a link gets the card,
  with the link and its QR code. That works for remote servers. A local
  server's link coming back to grognix itself only works from grog's own
  screen.
- **The card**, the same for all: the title ("Connect Spotify"), the link
  and its QR code, what to do now, a code when there's one, when it closes,
  Cancel; then "Spotify is connected (greg@...)", or why it isn't.

### The relay page

- Static, on GitHub Pages, built from the repo (`pages/oauth/`), at
  `https://ericdallo.github.io/grog/oauth/`. A fork has its own, set in the
  settings (`integrations.relay`).
- `client.json` is grog's client ID metadata document: its `client_id` is
  its own URL, `client_name` "grog", `redirect_uris` the callback page (and
  loopback, for signing in on grog's own screen),
  `token_endpoint_auth_method` "none" (grog is a public client). MCP
  authorization servers that support those documents take grog without
  anyone registering it, and the 2026-07-28 revision prefers them over
  dynamic registration, now deprecated.
- `callback/` reads `code`, `state` and `iss`. The state says where grog is
  (`<address>~<random>`: the address isn't secret, the random part binds the
  session). The page checks the address is private (10/8, 172.16/12,
  192.168/16, a `.local` name, link-local or ULA IPv6), then sends the
  browser there: `http://<address>/c/callback?code=...&state=...&iss=...`.
  When the browser won't go there, the page shows that address, to paste
  into grog's page.
- Chrome's Local Network Access (Chrome 142 on) asks permission for
  requests, subresources and frames from public pages to the local network.
  Top-level navigation wasn't among them in August 2026: check again when
  building, and keep pasting as the fallback.
- One page serves every grog: each one's address comes in the state. If
  it's down, signing in on grog's own screen still works (loopback).

### The runner

- `grog mcp`, a service of its own (NixOS: `services.grog.mcp`, on in
  grognix), with a dynamic user of its own, the network, `node` and `uv` on
  its PATH, a cache directory for npm and uv, and a state directory for
  servers that keep files, but no access to grog's state or anyone's home.
  It listens on a unix socket in `/run/grog-mcp`, which grog reaches
  through a shared group, like its hands, and finds in its settings
  (`integrations.runner`). Without it, grog has its own servers and remote
  ones only.
- grog asks it to start a server (registry, package, version, arguments,
  its environment, secrets included), and the connection then carries the
  server's stdio both ways. A server starts on first use, and stops after
  10 minutes without calls.
- Apart from grog for two reasons. grog's sandbox forbids memory that's
  both writable and executable (`MemoryDenyWriteExecute`, as Babashka needs
  no JIT), and Node's JIT needs it. And code downloaded at run time
  shouldn't share a process, a user or files with grog's tokens.
- On NixOS, prebuilt binaries in npm and PyPI packages (and uv's own
  Pythons) expect a standard Linux loader: grognix turns on
  `programs.nix-ld`, or uv uses nixpkgs' Python.
- Without NixOS (just the brain), it can run as the user, with their `npx`
  and `uvx`.

### What the model sees

- **Capability tools first**: `music`, `lights`, `computer`. Few,
  voice-friendly names that don't change, whatever is connected. They call
  what's behind them, from the catalog's capabilities (`HassTurnOff` for
  Home Assistant's lights), or grog's own players for music.
- **Other tools of connected servers** come prefixed by their integration
  (`ha__GetLiveContext`, 64 characters at most), only those the catalog
  shows: some servers have dozens, and each costs tokens on every turn. The
  user can show or hide more.
- **Guards**, from MCP's tool annotations, which the catalog can override:
  - a tool not marked `readOnlyHint` changes things: blocked in a turn that
    searched the web, like grog's own (`:changes?`);
  - one marked `destructiveHint` asks first: grog asks, and the next thing
    said answers ("Desligo o alarme?"), or Allow and Deny on the screen;
  - the web-search rule grows: after any tool whose result can carry
    someone else's text (`openWorldHint`: reading mail, a page), nothing
    changes in that turn.

  Tools of servers the catalog doesn't know count as changing things, and
  their destructive ones ask.
- **A policy per integration**, like a computer's `hands.json`: each tool
  allowed, asked or denied, with defaults from the annotations, changed on
  the screen.
- Never logged: arguments and results, as today.

### Music and targets

- **Targets**: grog (its default sink: its speaker, or a Bluetooth speaker
  paired with it), Spotify Connect devices (phones, computers, TVs,
  speakers), computers (`playerctl`), and later Cast devices.
- **The default target** is grog until the user sets another on the screen
  (a Bluetooth speaker) or asks ("a partir de agora toca na caixa da
  sala"). It's kept like the settings changed by asking (`settings.json`).
- **Spotify**:
  - **go-librespot** on grognix (`services.grog.music`, in the user's
    session like the voice and the hands, packaged in Nix: it's grog's
    body, not an MCP server) makes grog a Spotify Connect device (Premium
    needed). Its zeroconf login is the "another device authorizes" case:
    picking grog in the Spotify app hands it the account, which it keeps.
    Its REST API plays what grog asks and passes calls to Spotify's Web API
    with that session (search, devices, moving playback elsewhere), with no
    developer app. It's built on Spotify's private protocols (librespot),
    so it may break, and Spotify's terms frown on it.
  - **Spotify's own remote MCP server**, in pilot
    (`mcp-gateway-external-pilot.spotify.net/mcp`, OAuth): the cleanest path
    for controlling it, if it takes grog's client ID document. Check.
  - **The Web API with an app the user makes**: the official way. Since
    February 2026 its owner needs Premium, each app takes 5 users, and some
    endpoints are gone; since 2025, redirects must be HTTPS or `127.0.0.1`
    (the relay page qualifies).
- **YouTube Music** has no official API. Search through ytmusicapi
  (anonymous works; the user's library takes a Google client of type "TVs
  and Limited Input devices", the user's own, then a `device-code` step),
  played with mpv and yt-dlp on grog (`services.grog.music`). It breaks when
  YouTube changes.
- **Bluetooth**: pairing a speaker with grog from the screen or by voice
  (`bluetoothctl`, through grog's hands); then it can be the default.

### The voice, with music on grog

grog-voice is half duplex, and transcribes whatever its VAD takes for
speech; echo cancellation was measured and dropped (`plans/voice.md`).
With music coming out of grog's speaker, or a Bluetooth speaker near it, the
VAD fires on singing: Parakeet transcribes without stopping (CPU, on the
N5095), and grog hears its name less.

Before grog plays music itself by default:

- **Audio focus**: grog-voice pauses or lowers the music while it listens
  (after its name, or a touch), and while grog speaks, then brings it back.
- **Measure on grognix**: CPU and false wakes with music playing, at the
  volumes people use.
- **Echo cancellation again**, with the music as the reference (PipeWire's
  echo-cancel): dropped for grog's own voice, as it clipped and ate words,
  maybe worth it for music. Built (see "Where it stands"), with grog-voice
  told when music plays, to look for its name in short windows over it.
- Touching grog's face stops the music whatever happens.

Until it's measured, playing on another device works fully, and playing on
grog works but grog may hear its name less over loud music.

### Home Assistant

- Its built-in "Model Context Protocol Server" integration serves
  Streamable HTTP at `/api/mcp`, its tools coming from its Assist API
  (Stateless Assist recommended), limited to the entities exposed to
  Assist.
- Connecting:
  1. `discover` (`_home-assistant._tcp`), or the user types its address.
  2. grog checks `/api/mcp`. Without it, the page says how to add the
     integration (Settings, Devices & services, Add integration, Model
     Context Protocol Server).
  3. `oauth` with Home Assistant's own sign-in (`/auth/authorize`,
     `/auth/token`), grog's address on the network as the client id and
     the callback on that same host: no relay, nothing registered.
  4. grog keeps the tokens (an access token for half an hour, a refresh
     token), refreshes them, and sends them to `/api/mcp`.
  5. A long-lived token, pasted in a form, as the fallback.
- `lights` maps to its intents (`HassTurnOn`, `HassTurnOff`,
  `HassLightSet`); switches and climate later.
- Quick only when Home Assistant is already there: grog doesn't install it.

### Wi-Fi bulbs (Tuya, Smart Life)

- Tuya's device sharing SDK, what Home Assistant's Tuya integration uses
  since 2024.2, needs no developer account. The user copies the user code
  from the app (Me, settings, Account and Security, User Code), grog shows
  a QR code, the app scans it (+, Scan) and confirms. The bulbs are then
  driven through Tuya's cloud.
- It takes an MCP server built on that SDK (Python, `uvx`), fed the session
  grog keeps: an existing one, or grog's own, a small package on PyPI.
- Check whether Brazilian brands' apps (Positivo Casa Inteligente,
  Intelbras) work with it, or only Smart Life and Tuya Smart.

### Security

- **Who connects**: only someone at grog opens a session. Outside sessions
  and pairings, nothing of grog listens on the network.
- **The link** carries a random token, only shown on grog's screen and CLI.
  One session at a time, for 10 minutes.
- **OAuth**: PKCE always, the state bound to the session, the issuer
  checked, the resource named for MCP servers. The relay page sees codes,
  never tokens.
- **Plain HTTP on the network**: OAuth codes are safe there (PKCE), and so
  are device codes. A secret typed in a form isn't (someone reading the
  Wi-Fi could). So OAuth, device codes and app scans come first, and the
  page warns when a form asks for a secret.
- **Secrets**: in `secrets/`, 0600, readable by grog only, given to their
  server only (its environment, or its headers).
- **Code fetched at run time** runs in the runner, apart. The catalog's
  versions are pinned; a server the user adds is their call, as the screen
  says; a server only gets its own secrets.
- **Prompt injection**: what servers answer is untrusted text, like a web
  page: the guards above.
- **Remote servers** see what grog sends them: the catalog says which ones
  are in the cloud.

### The API, events, CLI and voice

- **API**, local only like the rest:
  - `GET /v1/integrations`: what can be connected, the connections and how
    they are, and the session.
  - `POST /v1/integrations/<id>/connect`: opens a session.
  - `DELETE /v1/connect`: cancels it.
  - `DELETE /v1/connections/<id>`: disconnects.
  - `POST /v1/integrations` `{registry name, package or url}`: adds a server;
    `DELETE /v1/integrations/<id>` removes one the user added.
  - `GET /v1/qr?text=...`: a QR code, as SVG (see below).
- **Events**: `integrations` (connections and how they are, no secrets) and
  `connect` (the session: integration, step, link, code, until, status,
  error).
- **Actions**: `connect`, with the integration, from the skin's
  Integrations; it replaces `pair`.
- **CLI**: `grog integrations`, `grog connect <id>`, `grog disconnect
  <id>`, `grog integrations add <package or url>`.
- **Voice**: a `connect` tool opens a session for an integration the catalog
  knows, and says the link is on the screen. Disconnecting by voice asks
  first.

### QR codes

The skin's QR codes are made when it's built, for the pages it knows
(`body/packages/grog-skin.nix`, `qrencode`), and its CSP only lets it load
its own files. A session's link changes every time, so grog makes the
codes itself, as SVG at `/v1/qr`: a small QR encoder in Babashka (byte
mode, error correction M, versions 1 to 10 hold a short link), checked
against `qrencode` in the package's tests. No dependency, and it works for
just the brain too.

### On NixOS

- `services.grog.mcp`: the runner (its user, its socket's group, `node`,
  `uv`, its cache), on in grognix, which also turns on `programs.nix-ld`.
- `services.grog.music`: go-librespot, mpv and yt-dlp, in the session of
  the user with the speaker, go-librespot's zeroconf port open in the
  firewall (grognix's avahi already answers mDNS).
- The pairing port (8485) serves every session's page; nothing else opens.
- Secrets stay out of Nix, as today: in grog's state directory.

### What becomes of what exists

- **The ChatGPT sign-in** becomes the `openai` integration's
  `device-code` step. `grog.login`'s engine becomes the `device-code` and
  `oauth` steps, and the provider reads its credentials from its
  connection. The skin's sign-in sheet becomes the card, which still shows
  by itself when grog has nothing to pay with.
- **Computers** become the `computer` integration: a server inside grog
  (its tool as today), its connections those of `computers.json`, its steps
  `script` and `code-compare` (pairing's listener and protocol as they
  are). The skin's pairing sheet becomes the card.
- `tools/available` lists the tools of grog's own servers and the connected
  ones; `actions`, `api`, `events`, `assistant` and `server` stop knowing
  about computers one by one.

## Phases

1. **Connecting, for what exists**: the engine (sessions, and the
   `device-code`, `oauth`, `form`, `code-compare` and `script` steps), the
   page on the network, QR codes in Babashka, the vault and
   `integrations.json`, `/v1/integrations`, the events, the CLI, the
   skin's card and its Integrations. The ChatGPT sign-in and computers move
   onto it. Nothing new to connect yet, and both work as before: that proves
   the shape.
2. **MCP**: the client (stdio through the runner, Streamable HTTP); the
   runner service (`npx`, `uvx`, nix-ld on grognix); the catalog as
   `server.json`; the model's tools (prefixes, which are shown, annotations
   as guards, the policy, asking first); elicitation on the card; OAuth for
   remote servers (protected resource metadata, client ID metadata
   documents, dynamic registration as the fallback, PKCE, issuer, resource)
   with the relay page on GitHub Pages; adding servers from the registry.
3. **Home Assistant**, and `lights`.
4. **Music**, partly done: the `music` tool and its providers, grog-music,
   and YouTube Music (October 2026). Left: targets and the default,
   go-librespot on grognix (Spotify Connect, zeroconf), Spotify's own MCP
   server if it takes grog, Bluetooth speakers from the screen.
5. **The voice's audio focus**, partly done: ducking, from grog-voice's
   states, in the brain. Left: measuring it on grognix (CPU, false wakes),
   and echo cancellation with the music as the reference, if it's needed
   for grog to hear its name over music.
6. **Wi-Fi bulbs** (Tuya).

## Later, not decided

- Whose account: several people at home, each with their Spotify (grog
  doesn't know who's talking).
- grog speaking up when something happens (a doorbell, a sensor, a
  finished download): MCP notifications and subscriptions, Home Assistant's
  events.
- Matter (grog as one more controller, with the code the Google Home or
  Apple Home app shares), Philips Hue (`press`), calendars, messages.
- Reaching grog from outside home: no.

## To check when building

- Checked in phase 2: the MCP revision at the time, 2026-07-28: client ID
  metadata documents, dynamic registration deprecated, the issuer check,
  resource indicators, elicitation's form and URL modes, tool annotations,
  and what's new in it (stateless, `server/discover`, `input_required`
  results; see "Where it stands").
- `npx` and `uvx` on NixOS: Node runs under the runner's hardening but for
  `MemoryDenyWriteExecute`, and grognix has nix-ld. Which servers need it
  is still to see, on real ones.
- Spotify: whether its own remote MCP server (pilot) takes grog's client ID
  document; Developer Mode's rules then; go-librespot's API, and whether
  zeroconf credentials are enough to start playback from its API; the
  terms.
- Browsers and the relay page: Chrome's Local Network Access on top-level
  navigation to a private address, and what Firefox and Safari do.
- Home Assistant, from its source (phase 3): `/api/mcp` takes any of its
  access tokens, those of its OAuth included, not only long-lived ones; and
  an `http` client id on the private network is accepted, the redirect on
  its host and port. Still to check on a real one: the whole sign-in, its
  tool names and annotations (whether `HassTurnOn` and `HassTurnOff` ask
  first), what `GetLiveContext` makes of the arguments `lights` sends it,
  `/api/mcp`'s admin-only setting, and the TXT discovery reads
  (`internal_url`, `location_name`).
- Tuya: Brazilian brands' apps, and an MCP server on the sharing SDK.
- Music on grognix: transcription's CPU, false wakes.
- mDNS from grog's sandbox (phase 3): avahi, through grognix's daemon, as
  the firewall drops unicast replies to grog's own queries; a legacy
  unicast query from Babashka as the fallback. Still to see on a real
  network, and with machines that answer only on IPv6.

## References

- MCP authorization, client registration (2026-07-28):
  https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration
- MCP elicitation, form and URL modes (2025-11-25):
  https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation
- MCP Registry, `server.json` and package types:
  https://modelcontextprotocol.io/registry/package-types
- Spotify redirect URIs:
  https://developer.spotify.com/documentation/web-api/concepts/redirect_uri
- Spotify's Developer Mode changes (February 2026):
  https://techcrunch.com/2026/02/06/spotify-changes-developer-mode-api-to-require-premium-accounts-limits-test-users/
- Spotify's remote MCP server (pilot): https://mcpservers.org/remote-mcp-servers/spotify
- go-librespot: https://github.com/devgianlu/go-librespot
- Home Assistant's MCP server integration:
  https://www.home-assistant.io/integrations/mcp_server/
- Home Assistant's Tuya integration, QR login:
  https://developer.tuya.com/en/docs/iot/Home-assistant-tuya-intergration?id=Kb0eqjig0utdd
- ytmusicapi's OAuth: https://ytmusicapi.readthedocs.io/en/stable/setup/oauth.html
- Local Network Access: https://wicg.github.io/local-network-access/
