# Signing key

`release.jks.enc` is the Gas Log release keystore, encrypted with AES-256 (OpenSSL, PBKDF2, 200,000
iterations). The GitHub Actions workflow decrypts it with the repository secret `GASLOG_KEY_PASSPHRASE`
and signs every release APK with it.

- Alias: `gaslog` (keystore and key use the same passphrase)
- SHA-1: `54:04:38:50:F4:87:ED:71:33:6F:74:86:DA:81:0C:FB:9E:FC:FB:39`
- SHA-256: `8B:A9:12:9C:B8:00:B5:58:36:82:91:86:C6:B0:BC:4A:5A:A5:93:D4:18:65:E8:E0:F8:40:47:39:9A:1B:56:6D`

The SHA-1 is what goes into the Android OAuth client in Google Cloud (package `com.gaslog.app`).

**Keep the passphrase safe** (a password manager is ideal). Every update must be signed with this same
key, or phones won't install it over the old version. Without the passphrase this file can't be opened.

To decrypt locally (e.g. to sign from Android Studio):

```
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in signing/release.jks.enc -out gaslog.jks
```
