# Chunk Claims: design

Fabric mod for Minecraft 26.3. Land claiming for a rule-free survival SMP: players buy
chunks with resources, the price climbs with every chunk they own, and claimed land is
protected from everyone the owner has not trusted.

The source request (pasted spec) is the approved description. This document records it
plus the calls made while building, marked **(call)**, so they can be checked and changed.

## Goals

- Progressive chunk economy: cheap starter resources first, diamonds later.
- Unclaim with a 50% refund.
- Client "editing mode" that paints claim state onto the ground.
- Two edit-mode actions: claim/unclaim the current chunk, and open a permissions menu.
- Per-cluster permissions: trust is set for a contiguous group of claimed chunks.
- Unauthorized players can walk in but cannot break, place, loot, steal or blow anything up.
- Server-authoritative networking, thread-safe claim store, persistent JSON storage.

## Non-goals

- PvP rules. Players keep vanilla PvP everywhere, including inside claims **(call)**.
- Mob griefing other than explosions (zombies on doors, endermen) **(call)**.
- Admin tooling, claim transfer, rent/tax, maps or web dashboards.
- Ground-item pickup protection. Items already lying on the ground are vanilla **(call)**.

## Packaging (call)

One jar with Loom's split source sets:

- `src/main`: common + server code (`ChunkClaims`, a `ModInitializer`). Runs on dedicated
  servers and on the integrated server in singleplayer.
- `src/client`: rendering, HUD, keybinds, the permissions screen (`ChunkClaimsClient`, a
  `ClientModInitializer`). Never loaded on a dedicated server.

The same jar goes on the server and on each client. A player without the mod can still
join and is still protected against (and restricted by) claims; they just get no UI.

## Economy

Price of the next chunk depends only on how many chunks the player owns now, summed across
all dimensions. Default ladder (all of it lives in `config/chunkclaims.json`) **(call)**:

| Chunk # | Cost |
|---|---|
| 1-2 | 32 Cobblestone |
| 3-4 | 16 Coal |
| 5-8 | 16 Iron Ingot |
| 9-12 | 16 Iron Ingot + 1 Diamond |
| 13-16 | 2 Diamond |
| 17-24 | 4 Diamond |
| 25-32 | 8 Diamond |
| 33-48 | 16 Diamond |
| 49+ | 32 Diamond, +8 for every 16 chunks past 48, capped at 64 |

Payment is taken from the main inventory and hotbar (never armor or offhand). All-or-nothing:
if the player is short on any item, nothing is taken. Creative-mode players claim for free
and get no refund **(call)**.

**Refund (call on the wording "50% of the current personal pricing tier"):** unclaiming
returns half (rounded down) of the price of the tier the player's most recent chunk sits
in, which is the price they would pay to buy that chunk back. Refunding half of the *next*
chunk's price instead would let players farm resources at tier boundaries. Refunds go into
the inventory; whatever does not fit drops at the player's feet.

## Placement rules

- A chunk is claimable when it is unowned, the dimension is allowed (default: all), and no
  chunk owned by a different player lies within `bufferChunks` (default 2) in Chebyshev
  distance, so diagonals count and there are always at least two free chunks between two
  players' land.
- Players can own several separate islands. A new claim does not have to touch an old one.
- Only the owner can claim, unclaim, or edit permissions. Trusted players cannot.

## Clusters and permissions

- A cluster is a set of same-owner, same-dimension chunks connected through shared edges
  (4-neighbour). Chunks that only touch at a corner are separate clusters **(call)**.
- Trust is stored on every chunk, and every chunk in a cluster carries the same trusted set.
- New isolated claim: nobody trusted.
- Claim that touches one cluster: inherits that cluster's trusted set.
- Claim that bridges several clusters **(call)**: the merged cluster gets the intersection,
  so only players trusted on every side keep access. Least privilege, nobody gains access
  to land the owner did not open to them.
- Unclaiming a bridge splits a cluster; each piece keeps the list it already had.
- Trusted players behave like the owner inside that cluster (vanilla survival), except
  for claiming, unclaiming and permissions.

## Protection

Applies to a player who is neither the owner nor trusted on the target chunk:

- Breaking blocks, and left-click interaction (note blocks, dragon egg, etc.).
- Placing blocks, including clicking a block outside the claim to place into it.
- All right-click block interaction: containers, doors, trapdoors, buttons, levers, beds,
  anvils, lecterns, jukeboxes, crafting stations **(call: everything, no allowlist)**.
- Buckets, boats and lily pads aimed into the claim.
- Entity interaction: item frames, armor stands, villagers, chest boats/minecarts, mounts.
- Hurting non-hostile entities in the claim (animals, pets, villagers, item frames, armor
  stands, paintings, vehicles), including with projectiles. Hostile mobs and players stay
  vanilla.
- Trampling farmland.

Environmental protection on claimed chunks, regardless of who caused it:

- Explosions never destroy or toggle blocks in a claim, and never hurt non-player,
  non-hostile entities there. This includes the owner's own TNT **(call)**.
- Pistons cannot push, pull or break blocks in a claim owned by someone other than the
  piston's own chunk owner.
- Fluids cannot flow into a claim from a chunk with a different owner (or no owner).
- Dispensers and droppers cannot fire into a claim from a chunk with a different owner.
- Fire never burns blocks away inside a claim.

## Client editing mode

Toggled with a hotkey; purely client-side.

| What | Look |
|---|---|
| Own territory | Blue outer border |
| Current chunk, own | Very light, subtle blue outline of that one chunk |
| Current chunk, unowned and claimable | Green ground fill |
| Current chunk, unowned and inside a buffer | Red ground fill |
| Buffer zones in view | Faint red line on their outer edge **(call: shows where you cannot claim before you walk there)** |
| Other players' territory | Purple outer border |

Borders are a ground strip plus a short vertical fade so they read from a distance. "Ground"
is the top of the first solid block found scanning down from just above the player, so the
overlay works on the surface and in caves **(call)**. View radius: 8 chunks.

A small HUD panel (edit mode only) shows what the current chunk is, the claim cost or
unclaim refund with item icons, the player's chunk count, and the key hints.

## Hotkeys (call)

Rebindable under Options > Controls > "Chunk Claims".

| Key | Action |
|---|---|
| G | Toggle editing mode |
| H | Claim the current chunk if green, unclaim it if yours (edit mode only) |
| J | Open the permissions menu for the cluster you stand in (edit mode only) |

## Permissions menu

The vanilla advancements window frame (252x140), titled with the cluster size. Scrollable
list (wheel and draggable scrollbar) of every player who has ever joined: anyone seen since
the mod was installed plus everyone in the server's `usercache.json` **(call)**. Each row:
online dot, name, check or cross toggle (vanilla beacon confirm/cancel sprites). A filter
box narrows the list. Clicks apply immediately; the owner is not listed.

## Networking

The server decides everything. The client never sends coordinates it wants claimed; it
sends intent and the server uses the player's real position.

Server to client:

- `chunkclaims:sync`: full claim list for the player's dimension (batched), sent on join,
  dimension change and respawn. Each entry: chunk x/z and an index into an owner table.
  The same payload carries deltas (chunks added or removed), broadcast to players in that
  dimension.
- `chunkclaims:status`: chunk count, next cost, current refund, buffer size.
- `chunkclaims:permissions`: data for the menu (anchor chunk, cluster size, player rows).

Client to server:

- `chunkclaims:action`: one of TOGGLE_CLAIM (claim or unclaim where I stand) or
  OPEN_PERMISSIONS (send me the menu for the cluster I stand in).
- `chunkclaims:set_permission`: set player X to allowed/denied in the cluster at anchor
  chunk C. The server checks the sender owns C, then echoes fresh menu data back.

Server messages are translation keys with English fallback text, so vanilla clients read
real sentences.

## Storage and threading

- `ClaimRegistry` (no Minecraft types, unit tested) guards all claim data with a
  read-write lock and keeps an owner index for O(1) counts.
- `<world>/chunkclaims/claims.json` holds claims, trust and known players. Written to a temp
  file and atomically moved into place; saved on a background thread a few seconds after a
  change and synchronously on server stop.
- `config/chunkclaims.json` holds prices, buffer size, allowed dimensions; created with the
  defaults above on first start.

## What is left to the builder

Exact colors and alpha, HUD layout, row height, message wording.
