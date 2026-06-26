# Universal Java License Client

## Location

```
license-java-client/
├── com/hubertstudios/license/
│   ├── License.java      — HTTP validation, signature verification, JAR hashing
│   └── LicenseGate.java  — Bukkit/Paper/Folia lifecycle wrapper
└── license.yml           — Template configuration file
```

## How to use in any plugin

### 1. Copy the two Java files

Copy `License.java` and `LicenseGate.java` into your plugin's source tree:

```
src/main/java/com/hubertstudios/license/
├── License.java
└── LicenseGate.java
```

No shading/relocation is required as long as each plugin has its own copy (or you shade + relocate the package to your plugin's namespace). The classes use only instance fields — no shared static state — so multiple copies on the same server cannot interfere.

### 2. Copy license.yml to resources

Copy `license-java-client/license.yml` to `src/main/resources/license.yml` in your plugin.
Fill in the `worker-url` and `public-key-pem` at deploy time (not at compile time).

```yaml
# license.yml — placed in src/main/resources/ and copied to the plugin data folder on first load.
license: "YOUR-LICENSE-KEY-HERE"
worker-url: "https://api.gg69nah.workers.dev"
public-key-pem: |
  -----BEGIN PUBLIC KEY-----
  <paste ECDSA P-256 public key from setup-tool.html>
  -----END PUBLIC KEY-----
recheck-interval-minutes: 60

# Optional: override server identity sent to the Worker for fingerprinting
server:
  public-ip: ""
  hostname: ""
  domain: ""
```

### 3. Integrate in onEnable / onDisable

```java
public class MyPlugin extends JavaPlugin {
    private LicenseGate licenseGate;

    @Override
    public void onEnable() {
        // Loads license.yml from the plugin data folder automatically.
        // Saves the bundled license.yml resource if the file does not exist yet.
        licenseGate = LicenseGate.fromLicenseYml(this);

        // Validates synchronously (safe in onEnable — brief network call).
        // If validation fails: logs the reason, disables the plugin, returns false.
        if (!licenseGate.validateBlockingAndStart()) {
            return; // plugin is already disabled — do not proceed with setup
        }

        // ... rest of onEnable (register commands, listeners, etc.) ...
    }

    @Override
    public void onDisable() {
        if (licenseGate != null) licenseGate.shutdown();
    }

    // Guard any sensitive feature:
    public void someAdminCommand() {
        if (!licenseGate.isArmed()) return; // gate checks trust window, no network call
        // ...
    }
}
```

### 4. Folia compatibility

`LicenseGate` uses a reflection-based scheduler bridge in `runOnServerThread()`:
- On **Folia**: detects `Server.getGlobalRegionScheduler()` via reflection and uses it.
- On **Paper/Bukkit**: falls back to `Bukkit.getScheduler().runTask()`.
- Background re-checks always run on a dedicated daemon thread — never on the main thread.

No unsafe `Bukkit.getScheduler()` calls are made from off-thread. The main thread callback is always routed through the detected scheduler.

## What License.java does

- **`validate(licenseKey, jarHash, serverInfo)`** — sends a signed POST to `/api/validate`, verifies the ECDSA-P256 response signature, returns a `License.Result`.
- **`hashJar(Path)`** — SHA-256 of the plugin JAR file (used for build hash validation).
- **`sha256Hex(String)`** — SHA-256 of any string (fallback for dev-classpath environments).
- **Signature verification** — supports both modern (8-field) and legacy (5-field) signature payloads automatically.

## Getting the public key

Run `backend/scripts/setup-tool.html` in a browser:
1. Click **Generate key pair**.
2. Copy the **Private Key PEM** → set as the Worker secret `PRIVATE_KEY`.
3. Copy the **Public Key PEM** → paste into each plugin's `license.yml` under `public-key-pem`.

Keep the private key secret. The public key is embedded in distributed plugin JARs and is not sensitive.

## Registering a build hash (if hash validation is enabled)

1. Build the plugin JAR: `mvn package`
2. Hash it: `sha256sum target/MyPlugin-1.0.jar`
3. In the dashboard → **Builds** → create a new build with:
   - Plugin: select the matching plugin
   - Hash: the SHA-256 hex string
   - Version: e.g. `1.0`
   - Active: ✓

The Worker checks the reported hash against registered active builds. If no hash matches, validation is rejected.

To disable hash validation globally: **Settings** → toggle **Require hash validation**.
To disable per-plugin: edit the plugin in the dashboard → uncheck **Require hash**.
