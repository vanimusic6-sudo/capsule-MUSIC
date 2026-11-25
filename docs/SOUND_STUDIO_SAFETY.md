# Capsule Sound Studio: safety boundaries

The software chain runs after Media3 time/pitch processing for supported PCM16 audio. It does not add a master-volume boost. The limiter targets -2 dBFS sample peaks and uses an approximate reconstructed-peak detector; this is not a certified true-peak meter. Frequency-dependent gain, linked lookahead, sustained signal-energy monitoring and the per-channel low/high-frequency and DC guard limit digital stress while effects are active. Neutral bypass intentionally preserves source samples.

The hearing-care ceiling uses Android routing and digital attenuation as proxies. If the route or volume lookup fails, it uses a conservative software ceiling when the feature is enabled. This cannot determine headphone sensitivity, amplifier gain, actual dBA, driver excursion, temperature, or hardware DSP after the app. The existing user preference remains respected; this is not a replacement for system hearing warnings.

WHO-ITU H.870 defines exposure references based on calibrated sound dose (75 dBA for 40 hours/week for the conservative mode, 80 dBA for adults). Digital dBFS and volume percentages are not interchangeable with dBA. See https://www.who.int/publications/i/item/9789241515276.

Regression checks exercise bounded PCM, sample alignment, channel isolation, intersample peak estimates, mix/tempo independence, transitions, and added relative energy/DC scenarios. Device-specific acoustic measurements, speaker thermal/excursion tests, real-device UI screenshots and latency/underrun measurements remain necessary before claiming physical or acoustic safety.
