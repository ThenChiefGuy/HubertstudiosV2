# HubertStudios Universal License Java Client

Drop `com/hubertstudios/license/License.java`, `LicenseGate.java`, and `license.yml` into any Bukkit/Paper/Folia plugin project.

Minimal plugin usage:

```java
private LicenseGate licenseGate;

@Override
public void onEnable() {
    licenseGate = LicenseGate.fromLicenseYml(this);
    if (!licenseGate.validateBlockingAndStart()) return;

    // continue normal plugin startup
}

@Override
public void onDisable() {
    if (licenseGate != null) licenseGate.shutdown();
}
```

The dashboard product name must match `plugin.yml`/`paper-plugin.yml` `name` exactly.
