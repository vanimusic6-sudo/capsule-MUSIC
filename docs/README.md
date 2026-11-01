# Documentation index

## Active contracts

- [Playback architecture](PLAYBACK_ARCHITECTURE.md): responsibilities, bounded fallback, cancellation, cache identity and audio/video separation.
- [Extractor maintenance](EXTRACTOR_MAINTENANCE.md): dependency ownership, adapter rules and failure-injection checks.
- [DEV signing](CAPSULE_DEV_SIGNING.md): development build identity and signing setup.
- [Native Android media integration](ANDROID_MEDIA_NOTIFICATION.md): platform integration context.
- [Scroll engine](SCROLL_ENGINE_20261101.md): Foundation gesture ownership and fixed scroll policy.
- [Security material](security/): supporting security evidence and checks.

## Recent verification reports

- [Extractor cleanup and fallback boundary verification](EXTRACTOR_CLEANUP_20261101.md).
- [Artwork, headers, search and Audius removal](ARTWORK_HEADER_SEARCH_20261101.md).
- [Playback/download review](PLAYBACK_DOWNLOAD_REVIEW_20261101.md).
- [Scroll pointer audit](SCROLL_POINTER_AUDIT_20261101.md).

## Historical evidence

Dated reports and task notes in this directory describe the revision, experiments and device recordings named inside them. They are retained for traceability, not as alternative implementations or current architecture specifications. In particular, older global bot-quarantine, disabled bounded-range, client-order and planned-refactor descriptions must not override the active contracts above.

When behavior changes, update the active contract and add a dated verification note. Keep old links usable rather than moving or deleting diagnostic evidence just to shorten the directory listing.
