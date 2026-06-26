# CoreGuard

Premium AntiDupe, AntiExploit and Staff Management plugin first implementation for Paper 1.21.x, Java 21+.

## Build

```bash
mvn clean package
```

The compiled plugin will be in:

```text
target/CoreGuard-1.0.0.jar
```

## Runtime files

On first start the plugin creates:

```text
plugins/CoreGuard/
├── config.yml
├── messages.yml
├── blacklist.yml
├── guis/
│   ├── inventory.yml
│   └── echest.yml
├── data/
│   ├── coreguard.db
│   └── backups/
└── logs/
```

## GUI configuration

The live inventory and ender chest GUI settings are not in the main `config.yml` anymore.

Edit these files instead:

```text
plugins/CoreGuard/guis/inventory.yml
plugins/CoreGuard/guis/echest.yml
```

This keeps the main config focused on security/punishment/dupe behavior and keeps GUI layout changes isolated.

## Implemented in this version

- `/cg`, `/cg help`, `/cg reload`
- PDC UUID fingerprints for unstackable items
- Optional stackable batch ledger for selected/custom materials
- `/cg iteminfo`
- `/cg scan <player>`
- `/cg blacklist add/remove/list`
- `/cg vanish [player]`
- `/cg freeze`, `/cg unfreeze`, `/cg freezestick`
- `/cg inventory <player>` live inventory GUI
- `/cg echest <player>` live ender chest GUI
- GUI configuration folder: `plugins/CoreGuard/guis/`
- `/cg staffmode`
- `/cg sc <message>`
- `/cg spy <player>` basic chat/command spy
- `/cg ban`, `/cg unban`, `/cg banip`, `/cg unbanip`
- `/cg mute`, `/cg unmute`
- `/cg warn`, `/cg warnings`, `/cg clearwarnings`
- `/cg kick`
- `/cg lookup`
- `/cg invbackup`, `/cg invrestore`
- `/cg history`
- SQLite schema for items, item history, players, punishments, warnings and audit log

## Ban enforcement design

CoreGuard does not rely only on UUID bans.

For custom-auth/offline-mode servers, the default config keeps name bans enabled. When a staff member bans an online player, CoreGuard also activates immediate lockdown. The lockdown blocks movement, inventory actions, chat, commands, dropping items and combat while repeated kick attempts are sent. This is meant to reduce the damage from clients that delay or ignore the first kick/disconnect packet.

Relevant config section:

```yaml
punishment-system:
  ban-enforcement:
    name-ban-enabled: true
    uuid-record-enabled: true
    ip-ban-on-player-ban: false
    immediate-lockdown-enabled: true
    repeat-kick-attempts: 5
    repeat-kick-every-ticks: 20
```

## Important limitations

This is source code, not a tested production jar. Build it locally with Java 21 and Maven.

The AntiDupe design intentionally does not treat stackable items like individual UUID-tracked items, because splitting a stack copies the same metadata to both stacks. Stackables are handled through an optional batch ledger instead.

The live inventory GUI writes directly to the target player's real inventory/ender chest, but its click handling is deliberately conservative: top-GUI slot swaps are supported; drag and complex shift-click behavior are cancelled to prevent unsafe writes.

## License system

CoreGuard now includes the HubertStudios runtime license gate.
Put the customer key into:

```yml
plugins/CoreGuard/license.yml
license: "xxx"
```

The plugin validates the license and the build hash on startup and repeats the check periodically.
