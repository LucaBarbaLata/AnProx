# 0015. Built-in SSH client on JSch, with Bouncy Castle and trust on first use

- Status: Accepted
- Date: 2026-10-15
- Questionnaire: Q15

## Context
Q15 asks for a built-in SSH client with key management, next to the Proxmox consoles. Unlike
the consoles, SSH doesn't go through the Proxmox API: the phone connects to the node's or
guest's own address. The client has to run on Android 8.0 (API 26), whose platform crypto lacks
Ed25519, Curve25519 and ChaCha20-Poly1305 (they arrive with API 33), while most people's keys
and modern OpenSSH defaults use them. It also has to be testable on the JVM like the rest of
`:core:data`.

## Options
1. **JSch** (the maintained `com.github.mwiede:jsch` fork). Pure Java, small, actively updated
   (OpenSSH key format, `rsa-sha2-*`, `curve25519`, ML-KEM). It has ready-made Bouncy Castle
   versions of the algorithms the platform lacks.
2. **sshj**. Pure Java too, but pulls in more (slf4j, a separate EdDSA library) and has had
   trouble with Android's own Bouncy Castle provider.
3. **ConnectBot's sshlib**. Built for Android, but depends on Tink, whose JVM artifact drags in
   protobuf; the Android artifact would make the JVM tests and the app use different builds.
4. A native library (libssh through JNI). Fast, but a native build for four ABIs and no JVM tests.

## Decision
Option 1, with `bcprov-jdk18on` for Ed25519, X25519, ChaCha20 and ML-KEM. JSch picks its
implementations by Java version and Android reports an old one, so `JschSetup` selects the
Bouncy Castle classes explicitly on every platform: the JVM tests run the same code as the phone.
Bouncy Castle is used through its lightweight API, not as a JCA provider, so it doesn't clash
with Android's own copy. JSch can't write Ed25519 private keys, so `SshKeys` writes new ones in
OpenSSH's own format.

- **Keys**: created on the phone (Ed25519 by default, ECDSA P-256, RSA 4096) or imported
  (OpenSSH, PEM, PuTTY; a passphrase is checked on import). The private half, and the passphrase
  if any, are stored with AES-256-GCM under a separate Android Keystore key (`anprox_ssh_v1`).
  Only the public half can be copied or shared.
- **Host keys: trust on first use.** An unknown key, or a different one for a known `host:port`,
  is shown with its SHA-256 fingerprint and the previous one; only an accepted key is stored.
  JSch asks on its connecting thread, which waits for the answer (`TrustOnFirstUse`).
- **Signing in**: a password (never stored) or a key. After a password sign-in, the app offers to
  add a key to `~/.ssh/authorized_keys` the way `ssh-copy-id` does, over the same connection;
  the command is built only from a validated key line, so nothing in it needs escaping.
- **Terminal**: the same xterm.js page and key row as the Proxmox terminals, through a
  `ConsoleConnection` interface that both the console WebSocket and the SSH shell implement.
- **Tests**: the mock server runs Apache MINA SSHD with a toy shell, so `:core:data` tests sign in
  with a password and keys, see a changed host key refused, and install a key.

## Consequences
- The APK grows by what R8 keeps of Bouncy Castle and JSch (JSch is kept whole, since it loads
  its algorithm classes by name).
- SSH works wherever the phone can reach the machine; behind a reverse proxy that only forwards
  HTTPS, it doesn't, and the Proxmox shell remains the way in.
- No SSH agent forwarding, port forwarding or SFTP yet; the client is built so they can be added
  on the same session.
