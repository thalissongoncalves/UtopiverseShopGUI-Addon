# UtopiverseShopGUI-Addon

Rotating sell-price boosts for **ShopGUIPlus**. One random item per group gets a multiplier for a configurable duration; timers roll over automatically.

## Requirements

- **Paper 1.21.11**
- **ShopGUIPlus**
- Optional: **ShopGUIPlus-SellGUI** (`/sellg`), **Vault**, **PlaceholderAPI**

## Install

1. Drop the built JAR in `plugins/`.
2. Start the server once, then edit `plugins/UtopiverseShopGUI-Addon/config.yml`.
3. Use `/rotatesell reload` after changes.

## Commands

| Command | Permission | Description |
|--------|------------|-------------|
| `/rotatesell reload` | `rotatesell.admin` | Reload config and rotation data |
| `/rotatesell info <group>` | same | Active item, multiplier, time left |
| `/rotatesell rotate <group>` | same | Force a new roll for that group |
| `/rotatesell debug <player> <MATERIAL>` | same | Shop sell price snapshot for that player |

## Build

```bash
mvn clean package
```

Artifact: `target/UtopiverseShopGUI-Addon-1.0-SNAPSHOT.jar` (name may follow `pom.xml`).

## SellGUI notes

If SellGUI is enabled, the addon loads **before** it (`plugin.yml` `loadbefore`) so inventory snapshots stay reliable. Tune `sellgui-integration` delays in config if payouts credit late on your economy plugin.
