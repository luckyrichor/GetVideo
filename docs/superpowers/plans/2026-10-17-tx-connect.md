# TX GetVideo connection implementation plan

> Execute inline with executing-plans; user authorized server synchronization and phone connectivity in this chat.

Goal: synchronize the current local GetVideo source to TX, start an isolated API, and verify phone HTTPS connectivity.

Architecture: existing TX host; independent loopback API port 8900, HTTPS port 8443, certificate/private key, encryption key, database and nginx credentials. GetAudio remains on 8800/443. Use the existing public HTTP ACME challenge location only to validate a separately issued IP certificate.

- [x] Back up TX source/config and SQLite consistently. Synchronize only versioned source and new source files; exclude user data, environments, Git internals and artifacts. Verify source checksums.
- [x] Provision independent GetVideo runtime configuration, systemd API, TLS certificate, nginx site and renewal timer. Validate nginx before reload; retain rollback backups. Check GetAudio health before/after. Enable only the API, not scheduled crawling.
- [x] Update Android production server policy and settings copy; preserve explicit local-only choice and custom servers. Run policy regression, Android unit tests/build, server suite; install same-signature update and test phone connection without ADB reverse.
- [x] Verify TLS, unauthenticated account rejection, platform capability response, phone-origin nginx requests, stored server URL, two existing phone records. Document any unavailable third-party authorization.

Review focus: port/firewall reachability, independent credentials, no account token leakage to GetAudio, certificate renewal, rollback, and preserved local videos.

Verification: server 163 passed; Android 58 passed and debug/release built. Phone 0.1.2 saved HTTPS endpoint and returned connection success without ADB reverse. Independent certificate renewal dry-run passed. Whole deployment review found no blocking issues. User confirmed TCP 8443 public firewall rule before submission.
