# CoreGuard Quality Notes

## Local checks performed here

- YAML syntax checked for all resource `.yml` files.
- Java source brace/package consistency checked with a script.
- `TimeParser.java` compiled standalone with Java 21.
- Full Maven build could not be executed in this environment because Maven and Paper dependencies are not installed locally.

## Required before publishing

Run locally:

```bash
mvn clean package
```

Then test on a fresh Paper 1.21.x server:

1. First boot with `license.yml` placeholder should fail cleanly.
2. Boot with a valid license should enable.
3. Test `/cg help`, `/cg reload`, `/cg staffmode`.
4. Test staff mode enable/disable and disconnect while staff mode is active.
5. Test `/cg inventory <player>` and `/cg echest <player>` with normal click, shift-click, number key, drag and close.
6. Test `/cg freeze`, `/cg unfreeze`, freeze stick uses and command blocking.
7. Test `/cg vanish`, relog while vanished and quit/join message hiding.
8. Test `/cg ban`, reconnect attempt, lockdown, repeated kick and `/cg unban`.
9. Test unstackable duplicate detection by manually copying a fingerprinted item.
10. Test stackable ledger only after enabling it for selected materials.

## Known design limits

- Stackable items cannot safely use item-level UUIDs. CoreGuard uses optional batch tracking instead.
- Full-world hidden-item detection is not implemented. Items are detected when touched/scanned/loaded through supported paths.
- Live inventory GUI intentionally blocks complex inventory operations to reduce dupe risk.
