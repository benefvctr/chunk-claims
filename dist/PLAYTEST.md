# Chunk Claims: install and playtest

This mod was built and checked by automated tests, but nobody has played it on a real
server yet. Twenty minutes with two players will tell us if anything is off.

## Install

You need Minecraft **26.3** with the Fabric Loader (0.20.1 or newer).

1. Download `chunkclaims-1.0.1.jar` from https://github.com/benefvctr/chunk-claims/releases/latest
   and Fabric API for 26.3 from https://modrinth.com/mod/fabric-api (version
   `0.165.0+26.3` or newer).
2. Put **both** `chunkclaims-1.0.1.jar` and the Fabric API jar into the `mods` folder of:
   - the server, and
   - every player who wants the claim overlay and menu.
3. Start the server once. It creates `config/chunkclaims.json` with the default prices.
   Change prices or the buffer there if you like, then restart.

Use `chunkclaims-1.0.1.jar`, not `chunkclaims-1.0.1-sources.jar`. When updating, delete the
old `chunkclaims-1.0.0.jar` from every `mods` folder first: two versions side by side stop
the game from starting.

## Keys

- **G**: toggle claim editing mode
- **H**: claim the chunk you stand in (when green), or unclaim it (when yours)
- **J**: permissions menu for the territory you stand in

They can be rebound under Options > Controls > Key Binds > Chunk Claims.

## Checklist (two players, A and B, both in survival)

Give A some cobblestone, coal, iron and a few diamonds.

**Claiming**
- [ ] A presses G. A panel appears top-left and the ground under A is green.
- [ ] A presses H. 32 cobblestone disappears, the chunk gets a blue border, and a faint
      light-blue outline marks the chunk A stands in.
- [ ] A claims a few more chunks next to it. The price climbs (cobblestone, then coal,
      then iron). The blue border wraps the whole area, not each chunk.
- [ ] A unclaims one chunk with H and gets half of the last price back.

**Buffer**
- [ ] B turns on G and walks toward A's land. A's land has a purple border for B.
- [ ] Within 2 chunks of A's land the ground turns red for B, and H says it is too close.

**Protection** (B is not trusted yet)
- [ ] B walks into A's land (allowed), but cannot break or place blocks there.
- [ ] B cannot open A's chests, use doors or buttons, or take items from item frames.
- [ ] B cannot hurt A's animals, including with a bow.
- [ ] B cannot pour lava or water into A's land, from inside or from just outside the edge.
- [ ] TNT set off by B next to (or inside) A's land does not break A's blocks.

**Permissions**
- [ ] A stands in their land, presses G, then J. A window like the advancements screen
      lists every player who has joined, each with a red cross.
- [ ] A clicks B's row. It turns into a green check.
- [ ] B can now build and open chests in that territory, but still cannot claim or
      unclaim A's chunks.
- [ ] If A has a second, separate territory, B is still blocked there.

**Restart**
- [ ] Restart the server. Claims and permissions are still there.

## If something breaks

Send back:
- which checklist item failed and what happened instead,
- a screenshot if it is visual,
- `logs/latest.log` from the server (and from the client if the problem was on screen).
