# OrbitalStrike

Paper/Folia plugin (Java 21, Maven) implementing `/orbital` with four
configurable orbital strike types: `dog`, `wither`, `nuke`, `strike`.

## Build

This was written and reviewed without a working compiler in the sandbox it
was built in (no network access to `repo.papermc.io`, no `mvn`/`javac`
available there). Every non-trivial API call was manually verified against
current Paper/Spigot Javadocs, but **you must run a real build yourself**
before deploying:

```bash
mvn clean package
```

The resulting jar will be at `target/OrbitalStrike-1.0.0.jar`. Drop it into
your server's `plugins/` folder.

Requirements:
- Java 21+
- A Paper or Folia server jar, version 1.21 or newer
- Internet access to `repo.papermc.io` during the build (Maven downloads the
  Paper API automatically via the `<repositories>` block in `pom.xml`)

## If the build fails

Most likely culprits, roughly in order of likelihood, if `mvn package`
reports an error:

1. **Wolf.Variant / Registry lookups** (`DogConfig.java`) - this project
   uses `Registry.WOLF_VARIANT.get(NamespacedKey.minecraft(...))` because
   `Wolf.Variant` stopped being a plain Java enum in 1.20.5+. If your exact
   Paper version renamed or moved this registry, this is the first place
   to check.
2. **Folia scheduler method signatures** (`FoliaUtil.java`) - the
   `RegionScheduler`/`EntityScheduler` APIs were verified against Paper
   1.21.x Javadocs, but Paper occasionally adjusts these between minor
   versions. If you get "method not found" errors here, check
   `https://jd.papermc.io/paper/<your-version>/io/papermc/paper/threadedregions/scheduler/`.
3. **BlockDisplay / Transformation usage** (`TargetingService.java`,
   `SingleStrike.java`) - uses `org.joml.Vector3f` / `AxisAngle4f`, which
   come transitively from the Paper API's bundled JOML dependency. If your
   IDE can't resolve `org.joml`, make sure you're building with Maven
   (which pulls the full dependency tree) rather than just compiling the
   source files directly.
4. **PotionEffectType.getByName()** (`BaseStrikeConfig.java`) - this is
   deprecated (in favor of `Registry.EFFECT`) but still functional. You'll
   see deprecation warnings during build; these are expected and harmless.

## What to test first in-game

1. `/orbital strike` on yourself - simplest type, single impact, good smoke
   test for the whole rod/reel/fire pipeline.
2. `/orbital dog` - no raycast involved, spawns wolves around you; good
   second test since it isolates targeting issues from strike-type issues.
3. `/orbital wither` - the most complex type (carrier flight + dome split +
   simultaneous launch); test last.
4. `/orbital nuke 1` then check the rod's lore/name/glow render correctly,
   then fire it.
5. `/orbital bomb strike <yourname>` - confirm instant-fire works and that
   `/orbital bomb dog <yourname>` is correctly rejected.
6. `/orbital reload` - confirm config changes (e.g. wolf-count) take effect
   without a server restart.

## Config

Everything is in `src/main/resources/config.yml` - rod appearance (material,
name, lore, glow) is defined **per strike type**, not globally. See the
comments inline for what each field controls. Reload with `/orbital reload`
(requires `orbitalstrike.admin`).

## Known design notes

- `dog` ignores any raycast target and always centers on the player it's
  triggered for (the caster for normal use; not reachable via `/orbital
  bomb`, which is hard-disabled for this type both in the command layer and
  in `DogConfig.allowBomb()`).
- `wither`'s dome uses an even rows×columns grid projected onto a
  half-sphere, not random scatter - this was a deliberate correction from an
  earlier random-distribution draft, to match the real machine's even,
  gapless area coverage.
- `nuke` and future ring/grid/random/line patterns reuse the same carrier-
  falls-then-splits trajectory logic in `GeometryUtil`.
- All entity spawns use real vanilla entities (Wolf, WitherSkull, TNTPrimed)
  and BlockDisplay markers instead of particle loops, per the performance
  requirement - there are no per-tick particle emitters anywhere in this
  plugin.
